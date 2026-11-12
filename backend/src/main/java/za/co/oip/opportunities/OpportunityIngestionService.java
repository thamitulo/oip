package za.co.oip.opportunities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.oip.crawling.adapter.AdapterResult.ItemError;
import za.co.oip.crawling.adapter.NormalisedOpportunity;
import za.co.oip.crawling.adapter.NormalisedOpportunity.DocumentLink;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.crawling.http.PoliteHttpClient;
import za.co.oip.opportunities.domain.Opportunity;
import za.co.oip.opportunities.domain.OpportunityCategory;
import za.co.oip.opportunities.domain.OpportunityCategoryRepository;
import za.co.oip.opportunities.domain.OpportunityDocument;
import za.co.oip.opportunities.domain.OpportunityDocumentRepository;
import za.co.oip.opportunities.domain.OpportunityEnums.ProcurementCategory;
import za.co.oip.opportunities.domain.OpportunityEnums.Provenance;
import za.co.oip.opportunities.domain.OpportunityRepository;
import za.co.oip.opportunities.domain.SourceChangeEvent;
import za.co.oip.opportunities.domain.SourceChangeEventRepository;
import za.co.oip.sources.domain.Source;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Persists normalised opportunities from a crawl: inserts new ones, detects and records changes to
 * existing ones, and links cross-source duplicates. Each item is stored in its own transaction so one bad
 * record cannot discard the rest of the run.
 *
 * <p>De-duplication: within a source, an opportunity is identified by {@code (source_id, source_reference)}.
 * Reference numbers are not assumed to be globally unique; across sources, a record is only linked as a
 * probable duplicate when the same organisation publishes the same normalised reference number.
 */
@Service
public class OpportunityIngestionService {

    private static final Logger log = LoggerFactory.getLogger(OpportunityIngestionService.class);

    /** Fields compared for change detection, with whether a change is material for tenants. */
    private static final Map<String, Boolean> TRACKED_FIELDS = new LinkedHashMap<>();

    static {
        TRACKED_FIELDS.put("title", true);
        TRACKED_FIELDS.put("referenceNumber", true);
        TRACKED_FIELDS.put("closingDate", true);
        TRACKED_FIELDS.put("closingAt", true);
        TRACKED_FIELDS.put("status", true);
        TRACKED_FIELDS.put("briefingDetails", true);
        TRACKED_FIELDS.put("briefingCompulsory", true);
        TRACKED_FIELDS.put("briefingAt", true);
        TRACKED_FIELDS.put("submissionMethod", true);
        TRACKED_FIELDS.put("submissionAddress", true);
        TRACKED_FIELDS.put("description", false);
        TRACKED_FIELDS.put("eventType", false);
        TRACKED_FIELDS.put("procurementCategory", false);
        TRACKED_FIELDS.put("locality", false);
        TRACKED_FIELDS.put("province", false);
        TRACKED_FIELDS.put("estimatedValue", false);
        TRACKED_FIELDS.put("contactName", false);
        TRACKED_FIELDS.put("contactEmail", false);
        TRACKED_FIELDS.put("contactPhone", false);
        TRACKED_FIELDS.put("sourceUrl", false);
    }

    private static final Map<String, Function<Opportunity, Object>> ENTITY_GETTERS = Map.ofEntries(
            Map.entry("title", Opportunity::getTitle),
            Map.entry("referenceNumber", Opportunity::getReferenceNumber),
            Map.entry("closingDate", Opportunity::getClosingDate),
            Map.entry("closingAt", Opportunity::getClosingAt),
            Map.entry("status", Opportunity::getStatus),
            Map.entry("briefingDetails", Opportunity::getBriefingDetails),
            Map.entry("briefingCompulsory", Opportunity::getBriefingCompulsory),
            Map.entry("briefingAt", Opportunity::getBriefingAt),
            Map.entry("submissionMethod", Opportunity::getSubmissionMethod),
            Map.entry("submissionAddress", Opportunity::getSubmissionAddress),
            Map.entry("description", Opportunity::getDescription),
            Map.entry("eventType", Opportunity::getEventType),
            Map.entry("procurementCategory", Opportunity::getProcurementCategory),
            Map.entry("locality", Opportunity::getLocality),
            Map.entry("province", Opportunity::getProvince),
            Map.entry("estimatedValue", Opportunity::getEstimatedValue),
            Map.entry("contactName", Opportunity::getContactName),
            Map.entry("contactEmail", Opportunity::getContactEmail),
            Map.entry("contactPhone", Opportunity::getContactPhone),
            Map.entry("sourceUrl", Opportunity::getSourceUrl));

    private final OpportunityRepository opportunityRepository;
    private final OpportunityDocumentRepository documentRepository;
    private final OpportunityCategoryRepository categoryRepository;
    private final SourceChangeEventRepository changeEventRepository;
    private final OrganisationResolver organisationResolver;
    private final za.co.oip.classification.MiningRelevanceClassifier miningClassifier;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OpportunityIngestionService(OpportunityRepository opportunityRepository,
                                       OpportunityDocumentRepository documentRepository,
                                       OpportunityCategoryRepository categoryRepository,
                                       SourceChangeEventRepository changeEventRepository,
                                       OrganisationResolver organisationResolver,
                                       za.co.oip.classification.MiningRelevanceClassifier miningClassifier,
                                       PlatformTransactionManager transactionManager, Clock clock) {
        this.opportunityRepository = opportunityRepository;
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.changeEventRepository = changeEventRepository;
        this.organisationResolver = organisationResolver;
        this.miningClassifier = miningClassifier;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public enum ItemOutcome { DISCOVERED, UPDATED, UNCHANGED }

    public record IngestionStats(int discovered, int updated, int unchanged, int rejected, List<ItemError> errors,
                                 List<UUID> changedOpportunityIds) {
    }

    public IngestionStats ingest(Source source, UUID runId, List<NormalisedOpportunity> items) {
        int discovered = 0, updated = 0, unchanged = 0, rejected = 0;
        List<ItemError> errors = new ArrayList<>();
        List<UUID> changed = new ArrayList<>();
        Set<String> seenInRun = new java.util.HashSet<>();
        for (NormalisedOpportunity item : items) {
            if (!seenInRun.add(item.sourceReference())) {
                // The same record listed twice on one page: keep the first.
                continue;
            }
            try {
                Result r = transactionTemplate.execute(status -> ingestOne(source, runId, item));
                switch (Objects.requireNonNull(r).outcome()) {
                    case DISCOVERED -> discovered++;
                    case UPDATED -> {
                        updated++;
                        changed.add(r.opportunityId());
                    }
                    case UNCHANGED -> unchanged++;
                }
            } catch (RuntimeException e) {
                rejected++;
                log.warn("Rejected item {} from source {}: {}", item.sourceReference(), source.getName(), e.getMessage());
                errors.add(new ItemError("PERSISTENCE_FAILED", e.getMessage(), item.sourceUrl(), item.sourceReference()));
            }
        }
        return new IngestionStats(discovered, updated, unchanged, rejected, errors, changed);
    }

    private record Result(ItemOutcome outcome, UUID opportunityId) {
    }

    private Result ingestOne(Source source, UUID runId, NormalisedOpportunity item) {
        validate(item);
        Instant now = clock.instant();
        String hash = contentHash(item);
        var existing = opportunityRepository.findBySourceIdAndSourceReference(source.getId(), item.sourceReference());

        if (existing.isEmpty()) {
            Opportunity o = new Opportunity();
            o.setSourceId(source.getId());
            o.setSourceReference(item.sourceReference());
            o.setOrganisationId(organisationResolver.resolve(source, item));
            apply(item, o, now);
            classifyMining(source, item, o);
            o.setContentHash(hash);
            o.setFirstDiscoveredAt(now);
            o.setLastSeenAt(now);
            o.setLastCheckedAt(now);
            o.setLastRunId(runId);
            o.setCreatedAt(now);
            o.setUpdatedAt(now);
            linkCrossSourceDuplicate(o);
            opportunityRepository.save(o);
            syncDocuments(o, source, runId, item.documents(), now, false);
            recordSourceCategory(o, now);
            return new Result(ItemOutcome.DISCOVERED, o.getId());
        }

        Opportunity o = existing.get();
        o.setLastSeenAt(now);
        o.setLastCheckedAt(now);
        o.setLastRunId(runId);
        boolean documentsChanged = syncDocuments(o, source, runId, item.documents(), now, true);
        if (hash.equals(o.getContentHash())) {
            if (documentsChanged) {
                o.setLastChangedAt(now);
                o.setUpdatedAt(now);
                return new Result(ItemOutcome.UPDATED, o.getId());
            }
            return new Result(ItemOutcome.UNCHANGED, o.getId());
        }

        Map<String, Object> before = snapshot(o);
        apply(item, o, now);
        classifyMining(source, item, o);
        Map<String, Object> after = snapshot(o);
        boolean anyChange = documentsChanged;
        for (Map.Entry<String, Boolean> field : TRACKED_FIELDS.entrySet()) {
            Object oldValue = before.get(field.getKey());
            Object newValue = after.get(field.getKey());
            if (!Objects.equals(stringify(oldValue), stringify(newValue))) {
                recordChange(o, source, runId, field.getKey(), stringify(oldValue), stringify(newValue), field.getValue(), now);
                anyChange = true;
            }
        }
        o.setContentHash(hash);
        o.setUpdatedAt(now);
        if (anyChange) {
            o.setLastChangedAt(now);
        }
        return new Result(anyChange ? ItemOutcome.UPDATED : ItemOutcome.UNCHANGED, o.getId());
    }

    private static void validate(NormalisedOpportunity item) {
        if (item.sourceReference() == null || item.sourceReference().isBlank()) {
            throw new IllegalArgumentException("Missing source reference");
        }
        if (item.title() == null || item.title().isBlank()) {
            throw new IllegalArgumentException("Missing title");
        }
        if (item.sourceUrl() == null || item.sourceUrl().isBlank()) {
            throw new IllegalArgumentException("Missing source URL; every opportunity must link to its source");
        }
    }

    /**
     * An inferred OPEN status is downgraded to EXPIRED once the closing moment has passed, using the same
     * rule as {@link OpportunityRepository#expireClosed}: the exact closing instant when known, otherwise
     * the end of the closing date in South African time. Published statuses are never overridden here.
     */
    static za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus effectiveStatus(NormalisedOpportunity n, Instant now) {
        var status = n.status();
        if (status == za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus.OPEN && n.statusInferred()) {
            boolean passed = n.closingAt() != null ? n.closingAt().isBefore(now)
                    : n.closingDate() != null && n.closingDate().isBefore(java.time.LocalDate.ofInstant(now, ParsingSupport.SOUTH_AFRICA));
            if (passed) {
                return za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus.EXPIRED;
            }
        }
        return status;
    }

    private static void apply(NormalisedOpportunity n, Opportunity o, Instant now) {
        o.setReferenceNumber(truncate(n.referenceNumber(), 300));
        o.setTitle(truncate(n.title(), 1000));
        o.setDescription(n.description());
        o.setEventType(n.eventType());
        o.setProcurementCategory(n.procurementCategory() == null ? ProcurementCategory.NOT_SPECIFIED : n.procurementCategory());
        o.setSourceCategoryLabel(truncate(n.sourceCategoryLabel(), 300));
        o.setStatus(effectiveStatus(n, now));
        o.setStatusInferred(n.statusInferred());
        o.setProvince(n.province());
        o.setLocality(truncate(n.locality(), 300));
        o.setPublishedAt(n.publishedAt());
        // A closing date found in a document (or set by a person) is kept when the listing itself has none.
        boolean keepDerivedClosing = n.closingDate() == null && o.getClosingDate() != null
                && o.getClosingProvenance() != null && o.getClosingProvenance() != Provenance.SOURCE;
        if (!keepDerivedClosing) {
            o.setClosingAt(n.closingAt());
            o.setClosingDate(n.closingDate());
            o.setClosingTimeSpecified(n.closingTimeSpecified());
            o.setClosingRaw(truncate(n.closingRaw(), 200));
            o.setClosingProvenance(n.closingDate() == null ? null : Provenance.SOURCE);
        }
        o.setEstimatedValue(n.estimatedValue());
        o.setCurrency(n.currency());
        o.setBriefingDetails(n.briefingDetails());
        o.setBriefingCompulsory(n.briefingCompulsory());
        o.setContactName(truncate(n.contactName(), 300));
        o.setContactEmail(truncate(n.contactEmail(), 320));
        o.setContactPhone(truncate(n.contactPhone(), 100));
        var sub = n.submission() == null ? NormalisedOpportunity.Submission.NOT_SPECIFIED : n.submission();
        boolean keepDerivedSubmission = sub.method() == za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod.NOT_SPECIFIED
                && o.getSubmissionProvenance() != null && o.getSubmissionProvenance() != Provenance.SOURCE
                && o.getSubmissionProvenance() != Provenance.SOURCE_POLICY;
        if (keepDerivedSubmission) {
            sub = new NormalisedOpportunity.Submission(o.getSubmissionMethod(), o.getSubmissionChannel(), o.getSubmissionAddress(),
                    o.getTwoEnvelope(), o.getSubmissionProvenance(), o.getSubmissionEvidence());
        }
        o.setSubmissionMethod(sub.method());
        o.setSubmissionChannel(truncate(sub.channel(), 300));
        o.setSubmissionAddress(truncate(sub.address(), 1000));
        o.setSubmissionProvenance(sub.provenance());
        o.setSubmissionEvidence(truncate(sub.evidence(), 2000));
        o.setTwoEnvelope(sub.twoEnvelope());
        o.setBriefingAt(n.briefingAt());
        o.setBriefingVenue(truncate(n.briefingVenue(), 1000));
        o.setSpecialConditions(n.specialConditions());
        o.setSourceUrl(truncate(n.sourceUrl(), 2000));
        o.setEvidenceUrl(truncate(n.evidenceUrl(), 2000));
    }

    /** Public-sector opportunities with a mining subject are flagged (with evidence) for the mining view. */
    private void classifyMining(Source source, NormalisedOpportunity item, Opportunity o) {
        if (source.getSector() == za.co.oip.procurement.domain.ProcurementEnums.Sector.MINING) {
            o.setMiningRelated(true);
            o.setMiningEvidence("Published by a mining-sector source");
            return;
        }
        var evidence = miningClassifier.classify(item.title(), item.description(), item.organisationName());
        o.setMiningRelated(evidence.isPresent());
        o.setMiningEvidence(evidence.map(e -> truncate(e, 500)).orElse(null));
    }

    private void linkCrossSourceDuplicate(Opportunity o) {
        if (o.getOrganisationId() == null || o.getReferenceNumber() == null) {
            return;
        }
        String normalised = o.getReferenceNumber().replaceAll("\\s+", "").toUpperCase();
        if (normalised.length() < 5) {
            return; // too short to be a reliable identifier
        }
        opportunityRepository.findCrossSourceDuplicate(o.getOrganisationId(), o.getSourceId(), normalised)
                .ifPresent(original -> o.setDuplicateOfId(original.getId()));
    }

    /** Adds new documents and marks missing ones as removed. Returns true when the set changed. */
    private boolean syncDocuments(Opportunity o, Source source, UUID runId, List<DocumentLink> links, Instant now,
                                  boolean recordChanges) {
        Map<String, OpportunityDocument> existing = new HashMap<>();
        for (OpportunityDocument d : documentRepository.findByOpportunityIdOrderByFirstSeenAtAsc(o.getId())) {
            existing.put(d.getUrl(), d);
        }
        boolean changed = false;
        Set<String> current = new java.util.HashSet<>();
        for (DocumentLink link : links) {
            String url = truncate(link.url(), 2000);
            if (!current.add(url)) {
                continue;
            }
            OpportunityDocument doc = existing.get(url);
            if (doc == null) {
                doc = new OpportunityDocument();
                doc.setOpportunityId(o.getId());
                doc.setUrl(url);
                doc.setTitle(truncate(link.title(), 1000));
                doc.setDocumentType(ParsingSupport.classifyDocument(link.title() != null ? link.title() : url));
                doc.setFileFormat(link.format() != null ? link.format().toLowerCase() : ParsingSupport.fileFormat(url));
                doc.setPublishedAt(link.publishedAt());
                doc.setFirstSeenAt(now);
                doc.setLastSeenAt(now);
                documentRepository.save(doc);
                if (recordChanges) {
                    recordChange(o, source, runId, "documents", null,
                            "Added: " + (link.title() != null ? link.title() : url), true, now);
                    changed = true;
                }
            } else {
                doc.setLastSeenAt(now);
                if (doc.getRemovedAt() != null) {
                    doc.setRemovedAt(null);
                    changed = true;
                }
            }
        }
        for (OpportunityDocument doc : existing.values()) {
            if (!current.contains(doc.getUrl()) && doc.getRemovedAt() == null) {
                doc.setRemovedAt(now);
                recordChange(o, source, runId, "documents", "Removed: " + (doc.getTitle() != null ? doc.getTitle() : doc.getUrl()),
                        null, true, now);
                changed = true;
            }
        }
        return changed;
    }

    private void recordSourceCategory(Opportunity o, Instant now) {
        if (o.getProcurementCategory() == ProcurementCategory.NOT_SPECIFIED) {
            return;
        }
        OpportunityCategory c = new OpportunityCategory();
        c.setOpportunityId(o.getId());
        c.setCategoryType(OpportunityCategory.CategoryType.PROCUREMENT_CATEGORY);
        c.setCode(o.getProcurementCategory().name());
        c.setProvenance(OpportunityCategory.Provenance.SOURCE);
        c.setCreatedAt(now);
        categoryRepository.save(c);
    }

    private void recordChange(Opportunity o, Source source, UUID runId, String field, String oldValue, String newValue,
                              boolean material, Instant now) {
        SourceChangeEvent e = new SourceChangeEvent();
        e.setOpportunityId(o.getId());
        e.setSourceId(source.getId());
        e.setRunId(runId);
        e.setFieldName(field);
        e.setOldValue(oldValue);
        e.setNewValue(newValue);
        e.setMaterial(material);
        e.setDetectedAt(now);
        changeEventRepository.save(e);
    }

    private static Map<String, Object> snapshot(Opportunity o) {
        Map<String, Object> m = new HashMap<>();
        ENTITY_GETTERS.forEach((k, getter) -> m.put(k, getter.apply(o)));
        return m;
    }

    private static String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    /** Hash over the fields that matter for change detection (documents are compared separately). */
    static String contentHash(NormalisedOpportunity n) {
        String canonical = String.join("\u001f",
                String.valueOf(n.referenceNumber()), String.valueOf(n.title()), String.valueOf(n.description()),
                String.valueOf(n.eventType()), String.valueOf(n.procurementCategory()), String.valueOf(n.status()),
                String.valueOf(n.province()), String.valueOf(n.locality()), String.valueOf(n.closingAt()),
                String.valueOf(n.closingDate()), String.valueOf(n.estimatedValue()), String.valueOf(n.briefingDetails()),
                String.valueOf(n.briefingCompulsory()), String.valueOf(n.contactName()), String.valueOf(n.contactEmail()),
                String.valueOf(n.contactPhone()), String.valueOf(n.sourceUrl()), String.valueOf(n.submission()),
                String.valueOf(n.briefingAt()), String.valueOf(n.briefingVenue()), String.valueOf(n.specialConditions()));
        return PoliteHttpClient.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
