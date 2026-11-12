package za.co.oip.crawling.execution;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.CrawlerProperties;
import za.co.oip.crawling.adapter.AdapterRegistry;
import za.co.oip.crawling.adapter.AdapterResult;
import za.co.oip.crawling.adapter.AdapterResult.ItemError;
import za.co.oip.crawling.adapter.CrawlContext;
import za.co.oip.crawling.adapter.ProcurementSourceAdapter;
import za.co.oip.crawling.adapter.SourceDescriptor;
import za.co.oip.crawling.execution.CrawlRecords.CrawlError;
import za.co.oip.crawling.execution.CrawlRecords.CrawlJob;
import za.co.oip.crawling.execution.CrawlRecords.CrawlJobRun;
import za.co.oip.crawling.execution.CrawlRecords.JobStatus;
import za.co.oip.crawling.execution.CrawlRecords.RunStatus;
import za.co.oip.crawling.execution.CrawlRecords.SourceObservation;
import za.co.oip.crawling.execution.CrawlRecords.TriggerType;
import za.co.oip.crawling.http.PoliteHttpClient;
import za.co.oip.opportunities.OpportunityIngestionService;
import za.co.oip.opportunities.OpportunityIngestionService.IngestionStats;
import za.co.oip.procurement.domain.ProcurementOrganisation;
import za.co.oip.procurement.domain.ProcurementOrganisationRepository;
import za.co.oip.sources.domain.CrawlScheduleRepository;
import za.co.oip.sources.domain.Source;
import za.co.oip.sources.domain.SourceRepository;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs crawls: acquires a per-source lease, invokes the adapter outside any database transaction,
 * records evidence and errors, ingests results and updates scheduling state.
 *
 * <p>A run that fails is recorded with a specific failure outcome and never touches stored opportunities,
 * so a broken or unreachable source is never reported as "no opportunities".
 */
@Service
public class CrawlService {

    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);
    private static final Set<CrawlOutcome> TRANSIENT = EnumSet.of(CrawlOutcome.SOURCE_UNAVAILABLE,
            CrawlOutcome.RATE_LIMITED, CrawlOutcome.CRAWL_FAILED);

    private final SourceRepository sourceRepository;
    private final CrawlScheduleRepository scheduleRepository;
    private final CrawlJobRepository jobRepository;
    private final CrawlJobRunRepository runRepository;
    private final CrawlErrorRepository errorRepository;
    private final SourceObservationRepository observationRepository;
    private final ProcurementOrganisationRepository organisationRepository;
    private final AdapterRegistry adapterRegistry;
    private final OpportunityIngestionService ingestionService;
    private final PoliteHttpClient httpClient;
    private final CrawlerProperties properties;
    private final AuditService auditService;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;
    private final TaskExecutor crawlExecutor;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String instanceId = ManagementFactory.getRuntimeMXBean().getName();

    public CrawlService(SourceRepository sourceRepository, CrawlScheduleRepository scheduleRepository,
                        CrawlJobRepository jobRepository, CrawlJobRunRepository runRepository,
                        CrawlErrorRepository errorRepository, SourceObservationRepository observationRepository,
                        ProcurementOrganisationRepository organisationRepository, AdapterRegistry adapterRegistry,
                        OpportunityIngestionService ingestionService, PoliteHttpClient httpClient,
                        CrawlerProperties properties, AuditService auditService, JsonMapper jsonMapper,
                        MeterRegistry meterRegistry, @Qualifier("crawlExecutor") TaskExecutor crawlExecutor,
                        PlatformTransactionManager transactionManager, Clock clock) {
        this.sourceRepository = sourceRepository;
        this.scheduleRepository = scheduleRepository;
        this.jobRepository = jobRepository;
        this.runRepository = runRepository;
        this.errorRepository = errorRepository;
        this.observationRepository = observationRepository;
        this.organisationRepository = organisationRepository;
        this.adapterRegistry = adapterRegistry;
        this.ingestionService = ingestionService;
        this.httpClient = httpClient;
        this.properties = properties;
        this.auditService = auditService;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
        this.crawlExecutor = crawlExecutor;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Queues a manual crawl. The source must be crawl-permitted, but need not be enabled for scheduling. */
    public CrawlJob requestManualCrawl(UUID sourceId, UUID requestedBy) {
        CrawlJob job = tx.execute(status -> {
            Source source = sourceRepository.findById(sourceId).orElseThrow(() -> new NotFoundException("Source", sourceId));
            List<String> blockers = blockers(source);
            if (!blockers.isEmpty()) {
                throw new BusinessRuleException("Source cannot be crawled: " + String.join("; ", blockers));
            }
            auditService.record(null, "MANUAL_CRAWL_REQUESTED", "Source", sourceId, Map.of());
            return jobRepository.save(newJob(sourceId, TriggerType.MANUAL, requestedBy));
        });
        crawlExecutor.execute(() -> execute(job.getId()));
        return job;
    }

    /** Queues crawls for all enabled sources that are due. Called by the scheduler. */
    public int queueDueCrawls() {
        List<UUID> jobIds = tx.execute(status -> scheduleRepository.findDue(clock.instant()).stream()
                .map(s -> jobRepository.save(newJob(s.getSourceId(), TriggerType.SCHEDULED, null)).getId())
                .toList());
        jobIds.forEach(id -> crawlExecutor.execute(() -> execute(id)));
        return jobIds.size();
    }

    /** Runs a crawl synchronously and returns the run. Used by tests and the async paths above. */
    public CrawlJobRun crawlNow(UUID sourceId, TriggerType trigger, UUID requestedBy) {
        CrawlJob job = tx.execute(status -> jobRepository.save(newJob(sourceId, trigger, requestedBy)));
        return execute(job.getId());
    }

    /** Fetches and parses a source without persisting opportunities ("test source"). */
    public DryRunResult dryRun(UUID sourceId) {
        Source source = sourceRepository.findById(sourceId).orElseThrow(() -> new NotFoundException("Source", sourceId));
        List<String> blockers = blockers(source);
        if (!blockers.isEmpty()) {
            throw new BusinessRuleException("Source cannot be crawled: " + String.join("; ", blockers));
        }
        ProcurementSourceAdapter adapter = adapterRegistry.find(source.getAdapterType()).orElseThrow();
        CrawlContext context = new CrawlContext(httpClient, clock, properties.snapshotExcerptChars());
        AdapterResult result = safeCrawl(adapter, descriptor(source), context);
        return new DryRunResult(result.outcome(), result.message(), result.httpStatus(), result.opportunities().size(),
                result.opportunities().stream().limit(25).toList(), result.itemErrors(), context.logLines());
    }

    public record DryRunResult(CrawlOutcome outcome, String message, Integer httpStatus, int recordsFound,
                               List<za.co.oip.crawling.adapter.NormalisedOpportunity> preview,
                               List<ItemError> itemErrors, List<String> log) {
    }

    CrawlJobRun execute(UUID jobId) {
        Instant start = clock.instant();
        CrawlJob job = tx.execute(s -> jobRepository.findById(jobId).orElseThrow());
        UUID sourceId = job.getSourceId();
        String leaseOwner = instanceId + ":" + Thread.currentThread().getName();

        Integer acquired = tx.execute(s -> scheduleRepository.acquireLease(sourceId, leaseOwner, start,
                start.plus(properties.leaseDuration())));
        if (acquired == null || acquired == 0) {
            tx.executeWithoutResult(s -> {
                CrawlJob j = jobRepository.findById(jobId).orElseThrow();
                j.setStatus(JobStatus.SKIPPED);
                j.setSkipReason("Another crawl of this source is already running");
                j.setCompletedAt(clock.instant());
            });
            log.info("Skipped crawl job {}: source {} is already being crawled", jobId, sourceId);
            return null;
        }

        CrawlJobRun run = tx.execute(s -> {
            CrawlJob j = jobRepository.findById(jobId).orElseThrow();
            j.setStatus(JobStatus.RUNNING);
            CrawlJobRun r = new CrawlJobRun();
            r.setJobId(jobId);
            r.setSourceId(sourceId);
            r.setStartedAt(start);
            return runRepository.save(r);
        });

        CrawlContext context = new CrawlContext(httpClient, clock, properties.snapshotExcerptChars());
        try {
            Source source = tx.execute(s -> sourceRepository.findById(sourceId).orElseThrow());
            List<String> blockers = blockers(source);
            AdapterResult result;
            if (!blockers.isEmpty()) {
                result = AdapterResult.failure(CrawlOutcome.NOT_PERMITTED, String.join("; ", blockers), null);
            } else {
                ProcurementSourceAdapter adapter = adapterRegistry.find(source.getAdapterType()).orElseThrow();
                context.log("Crawling " + source.getName() + " with " + adapter.type());
                result = safeCrawl(adapter, descriptor(source), context);
            }
            IngestionStats stats = result.outcome().isSuccess()
                    ? ingestionService.ingest(source, run.getId(), result.opportunities())
                    : null;
            return complete(run.getId(), jobId, source, result, stats, context);
        } catch (RuntimeException e) {
            log.error("Crawl job {} for source {} failed unexpectedly", jobId, sourceId, e);
            Source source = tx.execute(s -> sourceRepository.findById(sourceId).orElseThrow());
            return complete(run.getId(), jobId, source,
                    AdapterResult.failure(CrawlOutcome.CRAWL_FAILED, "Unexpected error: " + e.getMessage(), null),
                    null, context);
        } finally {
            tx.executeWithoutResult(s -> scheduleRepository.releaseLease(sourceId, leaseOwner));
        }
    }

    private AdapterResult safeCrawl(ProcurementSourceAdapter adapter, SourceDescriptor descriptor, CrawlContext context) {
        try {
            return adapter.crawl(descriptor, context);
        } catch (RuntimeException e) {
            log.warn("Adapter {} threw for source {}", adapter.type(), descriptor.name(), e);
            return AdapterResult.failure(CrawlOutcome.PARSER_FAILED, "Adapter error: " + e.getMessage(), null);
        }
    }

    private CrawlJobRun complete(UUID runId, UUID jobId, Source source, AdapterResult result, IngestionStats stats,
                                 CrawlContext context) {
        CrawlOutcome outcome = classify(result, stats);
        return tx.execute(s -> {
            Instant now = clock.instant();
            CrawlJobRun run = runRepository.findById(runId).orElseThrow();
            run.setFinishedAt(now);
            run.setOutcome(outcome);
            run.setStatus(outcome.isSuccess() ? RunStatus.SUCCEEDED : RunStatus.FAILED);
            run.setHttpStatus(result.httpStatus());
            run.setContentHash(result.contentHash());
            run.setRecordsFound(result.opportunities().size());
            if (stats != null) {
                run.setRecordsDiscovered(stats.discovered());
                run.setRecordsUpdated(stats.updated());
                run.setRecordsUnchanged(stats.unchanged());
                run.setRecordsRejected(stats.rejected() + result.itemErrors().size());
            }
            run.setMessage(outcome == CrawlOutcome.PARSER_FAILED && result.message() == null
                    ? "Every listed item was rejected; the parser configuration is probably out of date"
                    : result.message());
            run.setExecutionLog(String.join("\n", context.logLines()));

            List<ItemError> errors = new ArrayList<>(result.itemErrors());
            if (stats != null) {
                errors.addAll(stats.errors());
            }
            if (!outcome.isSuccess()) {
                errors.add(new ItemError(outcome.name(), run.getMessage() == null ? outcome.name() : run.getMessage(),
                        source.getSourceUrl(), null));
            }
            for (ItemError e : errors) {
                CrawlError ce = new CrawlError();
                ce.setRunId(runId);
                ce.setSourceId(source.getId());
                ce.setErrorType(e.errorType());
                ce.setMessage(e.message() == null ? e.errorType() : e.message());
                ce.setUrl(e.url() == null ? null : e.url().substring(0, Math.min(2000, e.url().length())));
                ce.setItemReference(e.itemReference());
                ce.setOccurredAt(now);
                errorRepository.save(ce);
            }
            for (CrawlContext.Observation o : context.observations()) {
                SourceObservation so = new SourceObservation();
                so.setSourceId(source.getId());
                so.setRunId(runId);
                so.setUrl(o.url());
                so.setHttpStatus(o.httpStatus());
                so.setContentType(o.contentType());
                so.setContentHash(o.contentHash());
                so.setBytes(o.bytes());
                so.setFetchedAt(o.fetchedAt());
                so.setExcerpt(o.excerpt());
                observationRepository.save(so);
            }

            Source managed = sourceRepository.findById(source.getId()).orElseThrow();
            managed.setLastAttemptedCrawlAt(now);
            if (outcome.isSuccess()) {
                managed.setLastSuccessfulCrawlAt(now);
            }
            scheduleRepository.findBySourceId(source.getId()).ifPresent(schedule -> {
                if (outcome.isSuccess()) {
                    schedule.recordSuccess(now);
                } else {
                    schedule.recordFailure(now, TRANSIENT.contains(outcome));
                }
            });

            CrawlJob job = jobRepository.findById(jobId).orElseThrow();
            job.setStatus(JobStatus.COMPLETED);
            job.setCompletedAt(now);

            meterRegistry.counter("oip.crawl.runs", "source", source.getName(), "outcome", outcome.name()).increment();
            if (stats != null) {
                meterRegistry.counter("oip.crawl.opportunities.discovered", "source", source.getName())
                        .increment(stats.discovered());
                meterRegistry.counter("oip.crawl.opportunities.updated", "source", source.getName())
                        .increment(stats.updated());
            }
            log.info("Crawl of '{}' finished: outcome={} found={} new={} updated={} unchanged={} rejected={}",
                    source.getName(), outcome, run.getRecordsFound(), run.getRecordsDiscovered(),
                    run.getRecordsUpdated(), run.getRecordsUnchanged(), run.getRecordsRejected());
            return run;
        });
    }

    /**
     * Final outcome. A listing where every item was rejected is treated as a parser failure rather than
     * a successful empty crawl.
     */
    static CrawlOutcome classify(AdapterResult result, IngestionStats stats) {
        if (!result.outcome().isSuccess()) {
            return result.outcome();
        }
        int found = result.opportunities().size();
        int rejectedByAdapter = result.itemErrors().stream()
                .filter(e -> !e.errorType().equals("TRUNCATED") && !e.errorType().equals("PAGINATION_FAILED"))
                .toList().size();
        if (found == 0 && rejectedByAdapter > 0) {
            return CrawlOutcome.PARSER_FAILED;
        }
        if (found == 0) {
            return CrawlOutcome.SUCCESS_ZERO_RESULTS;
        }
        if (stats != null && stats.discovered() == 0 && stats.updated() == 0 && stats.rejected() == 0
                && rejectedByAdapter == 0) {
            return CrawlOutcome.NO_CHANGE;
        }
        return CrawlOutcome.SUCCESS;
    }

    private List<String> blockers(Source source) {
        List<String> blockers = new ArrayList<>(source.crawlBlockers());
        if (source.getAdapterType() != null && !adapterRegistry.isImplemented(source.getAdapterType())) {
            blockers.add("Adapter " + source.getAdapterType() + " is not implemented");
        }
        return blockers;
    }

    private SourceDescriptor descriptor(Source source) {
        JsonNode config = null;
        if (source.getParserConfig() != null) {
            try {
                config = jsonMapper.readTree(source.getParserConfig());
            } catch (JacksonException e) {
                throw new BusinessRuleException("Parser configuration is not valid JSON");
            }
        }
        String orgName = source.getOrganisationId() == null ? null : tx.execute(s ->
                organisationRepository.findById(source.getOrganisationId()).map(ProcurementOrganisation::getName).orElse(null));
        return new SourceDescriptor(source.getId(), source.getName(), source.getSourceUrl(), source.getAdapterType(),
                source.getRateLimitPerMinute(), config, orgName);
    }

    private CrawlJob newJob(UUID sourceId, TriggerType trigger, UUID requestedBy) {
        CrawlJob job = new CrawlJob();
        job.setSourceId(sourceId);
        job.setTriggerType(trigger);
        job.setRequestedBy(requestedBy);
        job.setRequestedAt(clock.instant());
        return job;
    }
}
