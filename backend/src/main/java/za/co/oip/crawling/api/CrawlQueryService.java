package za.co.oip.crawling.api;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.common.api.PageResponse;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.api.CrawlDtos.CrawlErrorDto;
import za.co.oip.crawling.api.CrawlDtos.CrawlRunDetailDto;
import za.co.oip.crawling.api.CrawlDtos.CrawlRunDto;
import za.co.oip.crawling.api.CrawlDtos.ObservationDto;
import za.co.oip.crawling.api.CrawlDtos.SourceHealthDto;
import za.co.oip.crawling.execution.CrawlErrorRepository;
import za.co.oip.crawling.execution.CrawlJobRepository;
import za.co.oip.crawling.execution.CrawlJobRunRepository;
import za.co.oip.crawling.execution.CrawlRecords.CrawlError;
import za.co.oip.crawling.execution.CrawlRecords.CrawlJob;
import za.co.oip.crawling.execution.CrawlRecords.CrawlJobRun;
import za.co.oip.crawling.execution.CrawlRecords.RunStatus;
import za.co.oip.crawling.execution.SourceObservationRepository;
import za.co.oip.opportunities.domain.OpportunityRepository;
import za.co.oip.sources.domain.CrawlSchedule;
import za.co.oip.sources.domain.CrawlScheduleRepository;
import za.co.oip.sources.domain.Source;
import za.co.oip.sources.domain.SourceRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CrawlQueryService {

    private final CrawlJobRunRepository runRepository;
    private final CrawlJobRepository jobRepository;
    private final CrawlErrorRepository errorRepository;
    private final SourceObservationRepository observationRepository;
    private final SourceRepository sourceRepository;
    private final CrawlScheduleRepository scheduleRepository;
    private final OpportunityRepository opportunityRepository;
    private final Clock clock;

    public CrawlQueryService(CrawlJobRunRepository runRepository, CrawlJobRepository jobRepository,
                             CrawlErrorRepository errorRepository, SourceObservationRepository observationRepository,
                             SourceRepository sourceRepository, CrawlScheduleRepository scheduleRepository,
                             OpportunityRepository opportunityRepository, Clock clock) {
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
        this.errorRepository = errorRepository;
        this.observationRepository = observationRepository;
        this.sourceRepository = sourceRepository;
        this.scheduleRepository = scheduleRepository;
        this.opportunityRepository = opportunityRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<CrawlRunDto> runs(UUID sourceId, RunStatus status, Pageable pageable) {
        Map<UUID, String> names = sourceNames();
        return PageResponse.of(runRepository.search(sourceId, status, pageable), r -> toDto(r, names));
    }

    @Transactional(readOnly = true)
    public CrawlRunDetailDto runDetail(UUID runId) {
        CrawlJobRun run = runRepository.findById(runId).orElseThrow(() -> new NotFoundException("Crawl run", runId));
        Map<UUID, String> names = sourceNames();
        List<CrawlErrorDto> errors = errorRepository.findByRunIdOrderByOccurredAtAsc(runId).stream()
                .map(e -> toDto(e, names)).toList();
        List<ObservationDto> observations = observationRepository.findByRunIdOrderByFetchedAtAsc(runId).stream()
                .map(o -> new ObservationDto(o.getId(), o.getUrl(), o.getHttpStatus(), o.getContentType(),
                        o.getContentHash(), o.getBytes(), o.getFetchedAt(),
                        o.getExcerpt() == null ? null : o.getExcerpt().substring(0, Math.min(4000, o.getExcerpt().length()))))
                .toList();
        return new CrawlRunDetailDto(toDto(run, names), errors, observations, run.getExecutionLog());
    }

    @Transactional(readOnly = true)
    public PageResponse<CrawlErrorDto> errors(UUID sourceId, Pageable pageable) {
        Map<UUID, String> names = sourceNames();
        return PageResponse.of(errorRepository.search(sourceId, pageable), e -> toDto(e, names));
    }

    @Transactional(readOnly = true)
    public List<SourceHealthDto> sourceHealth() {
        Instant now = clock.instant();
        return sourceRepository.findAll().stream()
                .sorted(Comparator.comparingInt(Source::getPriority).thenComparing(Source::getName))
                .map(s -> {
                    CrawlSchedule schedule = scheduleRepository.findBySourceId(s.getId()).orElse(null);
                    CrawlOutcome last = runRepository.findFirstBySourceIdOrderByStartedAtDesc(s.getId())
                            .map(CrawlJobRun::getOutcome).orElse(null);
                    return new SourceHealthDto(s.getId(), s.getName(), s.getIntegrationStatus(), s.isEnabled(),
                            s.getLastAttemptedCrawlAt(), s.getLastSuccessfulCrawlAt(), last,
                            schedule == null ? null : schedule.getConsecutiveFailures(),
                            schedule == null ? null : schedule.getNextRunAt(),
                            opportunityRepository.countBySourceId(s.getId()),
                            health(s, last, schedule, now));
                })
                .toList();
    }

    /** HEALTHY, STALE (no success within twice the interval), FAILING, NEVER_RUN or NOT_ACTIVE. */
    static String health(Source s, CrawlOutcome last, CrawlSchedule schedule, Instant now) {
        if (!s.isEnabled()) {
            return "NOT_ACTIVE";
        }
        if (last == null) {
            return "NEVER_RUN";
        }
        if (!last.isSuccess()) {
            return "FAILING";
        }
        int interval = schedule == null ? 1440 : schedule.getIntervalMinutes();
        if (s.getLastSuccessfulCrawlAt() == null
                || s.getLastSuccessfulCrawlAt().isBefore(now.minus(Duration.ofMinutes(2L * interval)))) {
            return "STALE";
        }
        return "HEALTHY";
    }

    private Map<UUID, String> sourceNames() {
        return sourceRepository.findAll().stream().collect(Collectors.toMap(Source::getId, Source::getName));
    }

    private CrawlRunDto toDto(CrawlJobRun r, Map<UUID, String> names) {
        CrawlJob job = jobRepository.findById(r.getJobId()).orElse(null);
        return new CrawlRunDto(r.getId(), r.getJobId(), r.getSourceId(), names.get(r.getSourceId()),
                job == null ? null : job.getTriggerType(), r.getStartedAt(), r.getFinishedAt(), r.getStatus(),
                r.getOutcome(), r.getRecordsFound(), r.getRecordsDiscovered(), r.getRecordsUpdated(),
                r.getRecordsUnchanged(), r.getRecordsRejected(), r.getHttpStatus(), r.getContentHash(), r.getMessage());
    }

    private static CrawlErrorDto toDto(CrawlError e, Map<UUID, String> names) {
        return new CrawlErrorDto(e.getId(), e.getRunId(), e.getSourceId(), names.get(e.getSourceId()), e.getErrorType(),
                e.getMessage(), e.getUrl(), e.getItemReference(), e.getOccurredAt());
    }
}
