package za.co.oip.crawling.adapter;

import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;
import za.co.oip.opportunities.domain.OpportunityEnums.ProcurementCategory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A procurement event as read from a source, normalised to the platform model. Only values actually
 * present in the source are populated; everything else stays {@code null}. Adapters must never invent
 * values such as contract value, closing time or requirements.
 *
 * @param sourceReference      stable identifier within the source (used for de-duplication)
 * @param closingAt            exact closing instant, only when the source gives a time
 * @param closingDate          closing date in South African local time
 * @param closingTimeSpecified whether the source gave a closing time (not just a date)
 * @param closingRaw           the closing value exactly as published (evidence)
 * @param statusInferred       true when status was derived (e.g. from the closing date) rather than published
 * @param sourceUrl            where a person can see this opportunity at the source
 * @param evidenceUrl          the exact URL from which this record was read
 * @param attributes           other raw source fields kept as evidence
 */
public record NormalisedOpportunity(
        String sourceReference,
        String referenceNumber,
        String title,
        String description,
        EventType eventType,
        ProcurementCategory procurementCategory,
        String sourceCategoryLabel,
        OpportunityStatus status,
        boolean statusInferred,
        String organisationName,
        String organisationExternalId,
        String province,
        String locality,
        Instant publishedAt,
        Instant closingAt,
        LocalDate closingDate,
        boolean closingTimeSpecified,
        String closingRaw,
        BigDecimal estimatedValue,
        String currency,
        String briefingDetails,
        Boolean briefingCompulsory,
        String contactName,
        String contactEmail,
        String contactPhone,
        String sourceUrl,
        String evidenceUrl,
        List<DocumentLink> documents,
        Map<String, String> attributes,
        Submission submission,
        Instant briefingAt,
        String briefingVenue,
        String specialConditions) {

    /** How bids are submitted, with where that information came from. */
    public record Submission(za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod method, String channel,
                             String address, Boolean twoEnvelope,
                             za.co.oip.opportunities.domain.OpportunityEnums.Provenance provenance, String evidence) {
        public static final Submission NOT_SPECIFIED = new Submission(
                za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod.NOT_SPECIFIED, null, null, null, null, null);
    }

    public record DocumentLink(String title, String url, String format, Instant publishedAt) {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; adapters fill in what they know. */
    public static final class Builder {
        private String sourceReference, referenceNumber, title, description, sourceCategoryLabel;
        private EventType eventType = EventType.OTHER;
        private ProcurementCategory procurementCategory = ProcurementCategory.NOT_SPECIFIED;
        private OpportunityStatus status = OpportunityStatus.UNKNOWN;
        private boolean statusInferred, closingTimeSpecified;
        private String organisationName, organisationExternalId, province, locality, closingRaw, currency;
        private Instant publishedAt, closingAt;
        private LocalDate closingDate;
        private BigDecimal estimatedValue;
        private String briefingDetails, contactName, contactEmail, contactPhone, sourceUrl, evidenceUrl;
        private Boolean briefingCompulsory;
        private List<DocumentLink> documents = List.of();
        private Map<String, String> attributes = Map.of();
        private Submission submission = Submission.NOT_SPECIFIED;
        private Instant briefingAt;
        private String briefingVenue, specialConditions;

        public Builder sourceReference(String v) { sourceReference = v; return this; }
        public Builder referenceNumber(String v) { referenceNumber = v; return this; }
        public Builder title(String v) { title = v; return this; }
        public Builder description(String v) { description = v; return this; }
        public Builder eventType(EventType v) { eventType = v; return this; }
        public Builder procurementCategory(ProcurementCategory v) { procurementCategory = v; return this; }
        public Builder sourceCategoryLabel(String v) { sourceCategoryLabel = v; return this; }
        public Builder status(OpportunityStatus v, boolean inferred) { status = v; statusInferred = inferred; return this; }
        public Builder organisation(String name, String externalId) { organisationName = name; organisationExternalId = externalId; return this; }
        public Builder province(String v) { province = v; return this; }
        public Builder locality(String v) { locality = v; return this; }
        public Builder publishedAt(Instant v) { publishedAt = v; return this; }
        public Builder closing(Instant at, LocalDate date, boolean timeSpecified, String raw) {
            closingAt = at; closingDate = date; closingTimeSpecified = timeSpecified; closingRaw = raw; return this;
        }
        public Builder estimatedValue(BigDecimal v, String ccy) { estimatedValue = v; currency = ccy; return this; }
        public Builder briefing(String details, Boolean compulsory) { briefingDetails = details; briefingCompulsory = compulsory; return this; }
        public Builder contact(String name, String email, String phone) { contactName = name; contactEmail = email; contactPhone = phone; return this; }
        public Builder sourceUrl(String v) { sourceUrl = v; return this; }
        public Builder evidenceUrl(String v) { evidenceUrl = v; return this; }
        public Builder documents(List<DocumentLink> v) { documents = List.copyOf(v); return this; }
        public Builder attributes(Map<String, String> v) { attributes = Map.copyOf(v); return this; }
        public Builder submission(Submission v) { submission = v == null ? Submission.NOT_SPECIFIED : v; return this; }
        public Builder briefingSchedule(Instant at, String venue) { briefingAt = at; briefingVenue = venue; return this; }
        public Builder specialConditions(String v) { specialConditions = v; return this; }

        public NormalisedOpportunity build() {
            return new NormalisedOpportunity(sourceReference, referenceNumber, title, description, eventType,
                    procurementCategory, sourceCategoryLabel, status, statusInferred, organisationName,
                    organisationExternalId, province, locality, publishedAt, closingAt, closingDate,
                    closingTimeSpecified, closingRaw, estimatedValue, currency, briefingDetails, briefingCompulsory,
                    contactName, contactEmail, contactPhone, sourceUrl, evidenceUrl, documents, attributes, submission,
                    briefingAt, briefingVenue, specialConditions);
        }
    }
}
