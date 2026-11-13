package za.co.oip.documents;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.crawling.http.FetchException;
import za.co.oip.crawling.http.FetchResult;
import za.co.oip.crawling.http.PoliteHttpClient;
import za.co.oip.documents.DocumentEntities.OpportunityRequirement;
import za.co.oip.documents.DocumentEntities.ProcessingStatus;
import za.co.oip.documents.DocumentEntities.SourceDocument;
import za.co.oip.opportunities.domain.Opportunity;
import za.co.oip.opportunities.domain.OpportunityDocument;
import za.co.oip.opportunities.domain.OpportunityDocumentRepository;
import za.co.oip.opportunities.domain.OpportunityEnums.Provenance;
import za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod;
import za.co.oip.opportunities.domain.OpportunityRepository;
import za.co.oip.opportunities.domain.SourceChangeEvent;
import za.co.oip.opportunities.domain.SourceChangeEventRepository;
import za.co.oip.sources.domain.Source;
import za.co.oip.sources.domain.SourceEnums.TermsReviewStatus;
import za.co.oip.sources.domain.SourceRepository;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Downloads opportunity documents, keeps the original, extracts text and derives requirements, submission
 * method and (when the listing has none) the closing date. Values from documents are recorded with provenance
 * DOCUMENT and never overwrite values the source published as structured fields.
 */
@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final OpportunityDocumentRepository documentRepository;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final OpportunityRequirementRepository requirementRepository;
    private final OpportunityRepository opportunityRepository;
    private final SourceChangeEventRepository changeEventRepository;
    private final SourceRepository sourceRepository;
    private final PoliteHttpClient http;
    private final TextExtractor textExtractor;
    private final RequirementExtractor requirementExtractor;
    private final DocumentStorage storage;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int retentionDays;

    private final long maxDownloadBytes;

    public DocumentProcessingService(OpportunityDocumentRepository documentRepository,
                                     SourceDocumentRepository sourceDocumentRepository,
                                     OpportunityRequirementRepository requirementRepository,
                                     OpportunityRepository opportunityRepository,
                                     SourceChangeEventRepository changeEventRepository, SourceRepository sourceRepository,
                                     PoliteHttpClient http, TextExtractor textExtractor,
                                     RequirementExtractor requirementExtractor, DocumentStorage storage,
                                     MeterRegistry meterRegistry, PlatformTransactionManager transactionManager, Clock clock,
                                     @Value("${oip.documents.original-retention-days:90}") int retentionDays,
                                     @Value("${oip.documents.max-download-bytes:62914560}") long maxDownloadBytes) {
        this.maxDownloadBytes = maxDownloadBytes;
        this.documentRepository = documentRepository;
        this.sourceDocumentRepository = sourceDocumentRepository;
        this.requirementRepository = requirementRepository;
        this.opportunityRepository = opportunityRepository;
        this.changeEventRepository = changeEventRepository;
        this.sourceRepository = sourceRepository;
        this.http = http;
        this.textExtractor = textExtractor;
        this.requirementExtractor = requirementExtractor;
        this.storage = storage;
        this.meterRegistry = meterRegistry;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    /** Processes up to {@code limit} queued documents. Returns how many were attempted. */
    public int processQueued(int limit) {
        Instant now = clock.instant();
        List<UUID> ids = tx.execute(s -> sourceDocumentRepository.findDocumentsToProcess(
                LocalDate.ofInstant(now, ParsingSupport.SOUTH_AFRICA), now, limit));
        ids.forEach(this::process);
        return ids.size();
    }

    /** Processes all documents of one opportunity now (used on demand from the UI). */
    public void processOpportunity(UUID opportunityId) {
        if (!opportunityRepository.existsById(opportunityId)) {
            throw new NotFoundException("Opportunity", opportunityId);
        }
        documentRepository.findByOpportunityIdOrderByFirstSeenAtAsc(opportunityId).stream()
                .filter(d -> d.getRemovedAt() == null)
                .limit(10)
                .forEach(d -> process(d.getId()));
    }

    /** Fetches, stores and extracts one opportunity document. Never throws. */
    public void process(UUID opportunityDocumentId) {
        OpportunityDocument doc = tx.execute(s -> documentRepository.findById(opportunityDocumentId).orElse(null));
        if (doc == null) {
            return;
        }
        Opportunity opportunity = tx.execute(s -> opportunityRepository.findById(doc.getOpportunityId()).orElseThrow());
        Source source = tx.execute(s -> sourceRepository.findById(opportunity.getSourceId()).orElseThrow());
        SourceDocument sd = tx.execute(s -> sourceDocumentRepository.findByOpportunityDocumentId(doc.getId())
                .orElseGet(() -> {
                    SourceDocument n = new SourceDocument();
                    n.setOpportunityDocumentId(doc.getId());
                    n.setOpportunityId(doc.getOpportunityId());
                    n.setCreatedAt(clock.instant());
                    n.setUpdatedAt(clock.instant());
                    return sourceDocumentRepository.save(n);
                }));
        if (source.getTermsReviewStatus() == TermsReviewStatus.PROHIBITED) {
            finish(sd.getId(), ProcessingStatus.SKIPPED, "Source terms prohibit downloading documents", null);
            return;
        }
        try {
            FetchResult fetched = http.get(URI.create(doc.getUrl()), source.getRateLimitPerMinute(), maxDownloadBytes);
            String key = "opportunity/" + opportunity.getId() + "/" + sd.getId();
            storage.put(key, fetched.body());
            TextExtractor.Extraction extraction = textExtractor.extract(fetched.body(), doc.getTitle());
            RequirementExtractor.Result findings = requirementExtractor.extract(extraction.text());
            tx.executeWithoutResult(s -> {
                SourceDocument managed = sourceDocumentRepository.findById(sd.getId()).orElseThrow();
                managed.setStatus(ProcessingStatus.EXTRACTED);
                managed.setContentType(extraction.contentType());
                managed.setSizeBytes((long) fetched.body().length);
                managed.setChecksumSha256(fetched.sha256());
                managed.setStorageKey(key);
                managed.setPageCount(extraction.pageCount());
                managed.setTextContent(extraction.text());
                managed.setTextTruncated(extraction.truncated());
                managed.setError(null);
                managed.setAttempts(managed.getAttempts() + 1);
                managed.setDownloadedAt(fetched.fetchedAt());
                managed.setExtractedAt(clock.instant());
                managed.setUpdatedAt(clock.instant());
                applyFindings(managed, findings);
            });
            meterRegistry.counter("oip.documents.processed", "outcome", "extracted").increment();
        } catch (TextExtractor.UnsupportedDocumentException e) {
            finish(sd.getId(), ProcessingStatus.SKIPPED, e.getMessage(), null);
            meterRegistry.counter("oip.documents.processed", "outcome", "skipped").increment();
        } catch (FetchException e) {
            finish(sd.getId(), ProcessingStatus.FAILED, "Download failed (" + e.outcome() + "): " + e.getMessage(),
                    clock.instant().plus(Duration.ofHours(6)));
            meterRegistry.counter("oip.documents.processed", "outcome", "download_failed").increment();
        } catch (RuntimeException e) {
            log.warn("Document {} failed: {}", doc.getUrl(), e.getMessage());
            finish(sd.getId(), ProcessingStatus.FAILED, truncate(e.getMessage(), 1900), clock.instant().plus(Duration.ofHours(6)));
            meterRegistry.counter("oip.documents.processed", "outcome", "extraction_failed").increment();
        }
    }

    private void applyFindings(SourceDocument sd, RequirementExtractor.Result findings) {
        Instant now = clock.instant();
        requirementRepository.deleteByDocumentAndProvenance(sd.getId(), Provenance.DOCUMENT);
        for (RequirementExtractor.Finding f : findings.requirements()) {
            OpportunityRequirement r = new OpportunityRequirement();
            r.setOpportunityId(sd.getOpportunityId());
            r.setRequirementType(f.type().name());
            r.setValue(truncate(f.value(), 500));
            r.setMandatory(f.mandatory());
            r.setProvenance(Provenance.DOCUMENT);
            r.setEvidence(truncate(f.evidence(), 2000));
            r.setSourceDocumentId(sd.getId());
            r.setCreatedAt(now);
            requirementRepository.save(r);
        }
        Opportunity o = opportunityRepository.findById(sd.getOpportunityId()).orElseThrow();
        // Submission: only fill in when the source did not state it.
        findings.submission().ifPresent(s -> {
            if (o.getSubmissionMethod() == SubmissionMethod.NOT_SPECIFIED || o.getSubmissionProvenance() == Provenance.DOCUMENT) {
                if (o.getSubmissionMethod() != s.method()) {
                    change(o, "submissionMethod", o.getSubmissionMethod().name(), s.method().name(), now);
                }
                o.setSubmissionMethod(s.method());
                o.setSubmissionProvenance(Provenance.DOCUMENT);
                o.setSubmissionChannel("As stated in the tender document");
                o.setSubmissionEvidence(truncate(s.evidence(), 2000));
            }
        });
        // Closing date: only when the listing has none.
        findings.closing().ifPresent(c -> {
            if (o.getClosingDate() == null) {
                o.setClosingDate(c.date());
                o.setClosingAt(c.dateTime() == null ? null : c.dateTime().atZone(ParsingSupport.SOUTH_AFRICA).toInstant());
                o.setClosingTimeSpecified(c.dateTime() != null);
                o.setClosingRaw(truncate(c.evidence(), 200));
                o.setClosingProvenance(Provenance.DOCUMENT);
                change(o, "closingDate", null, c.date().toString(), now);
            }
        });
        if (findings.requirements().stream().anyMatch(f -> f.type() == RequirementExtractor.Type.COMPULSORY_BRIEFING)
                && o.getBriefingCompulsory() == null) {
            o.setBriefingCompulsory(true);
        }
        o.setUpdatedAt(now);
    }

    private void change(Opportunity o, String field, String oldValue, String newValue, Instant now) {
        SourceChangeEvent e = new SourceChangeEvent();
        e.setOpportunityId(o.getId());
        e.setSourceId(o.getSourceId());
        e.setFieldName(field);
        e.setOldValue(oldValue);
        e.setNewValue(newValue + " (from document)");
        e.setMaterial(true);
        e.setDetectedAt(now);
        changeEventRepository.save(e);
        o.setLastChangedAt(now);
    }

    private void finish(UUID id, ProcessingStatus status, String error, Instant retryAt) {
        tx.executeWithoutResult(s -> sourceDocumentRepository.findById(id).ifPresent(sd -> {
            sd.setStatus(status);
            sd.setError(error);
            sd.setAttempts(sd.getAttempts() + 1);
            sd.setNextAttemptAt(retryAt);
            sd.setUpdatedAt(clock.instant());
        }));
    }

    /** Deletes stored originals of opportunities that closed more than the retention period ago. Text is kept. */
    public int expireOriginals() {
        LocalDate before = LocalDate.now(clock.withZone(ParsingSupport.SOUTH_AFRICA)).minusDays(retentionDays);
        return tx.execute(s -> {
            List<SourceDocument> docs = sourceDocumentRepository.findOriginalsToExpire(before);
            for (SourceDocument d : docs) {
                storage.delete(d.getStorageKey());
                d.setOriginalDeletedAt(clock.instant());
                d.setStorageKey(null);
            }
            return docs.size();
        });
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
