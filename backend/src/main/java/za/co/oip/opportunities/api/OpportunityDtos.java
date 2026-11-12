package za.co.oip.opportunities.api;

import za.co.oip.opportunities.domain.OpportunityEnums.DocumentType;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;
import za.co.oip.opportunities.domain.OpportunityEnums.ProcurementCategory;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.sources.domain.SourceEnums.IntegrationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class OpportunityDtos {

    private OpportunityDtos() {
    }

    /** Catalogue card. {@code sourceVerified} is true when the source's access has been verified. */
    public record OpportunitySummaryDto(UUID id, String title, String referenceNumber, EventType eventType,
                                        ProcurementCategory procurementCategory, OpportunityStatus status,
                                        boolean statusInferred, UUID organisationId, String organisationName,
                                        Sector sector, String province, String locality, LocalDate closingDate,
                                        Instant closingAt, boolean closingTimeSpecified, Long daysUntilClosing,
                                        String urgency, Instant publishedAt, Instant firstDiscoveredAt,
                                        UUID sourceId, String sourceName, boolean sourceVerified, String sourceUrl,
                                        int documentCount, za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod submissionMethod,
                                        boolean miningRelated, Boolean briefingCompulsory) {
    }

    /** How to submit, and where that information came from (source field, source policy, document text ...). */
    public record SubmissionDto(za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod method, String channel, String address, Boolean twoEnvelope,
                                za.co.oip.opportunities.domain.OpportunityEnums.Provenance provenance, String evidence) {
    }

    public record DocumentDto(UUID id, String title, String url, DocumentType documentType, String fileFormat,
                              Instant publishedAt, Instant firstSeenAt, Instant removedAt, UUID storedDocumentId,
                              String processingStatus, String processingError, Integer pageCount, boolean originalStored) {
    }

    public record ChangeEventDto(UUID id, String fieldName, String oldValue, String newValue, boolean material,
                                 Instant detectedAt) {
    }

    public record CategoryDto(String categoryType, String code, String provenance, BigDecimal confidence) {
    }

    /** Where the data came from and how fresh it is. Everything here is a recorded fact, not an inference. */
    public record EvidenceDto(String sourceName, String sourceUrl, String evidenceUrl, IntegrationStatus sourceStatus,
                              Instant sourceLastVerifiedAt, Instant firstDiscoveredAt, Instant lastCheckedAt,
                              Instant lastSeenAt, Instant lastChangedAt, String closingRaw, String contentHash,
                              String licenceNotes) {
    }

    public record OpportunityDetailDto(OpportunitySummaryDto summary, String description, String sourceCategoryLabel,
                                       BigDecimal estimatedValue, String currency, String contractDuration,
                                       String briefingDetails, Boolean briefingCompulsory, String contactName,
                                       String contactEmail, String contactPhone, String mineSite,
                                       UUID duplicateOfId, List<DocumentDto> documents, List<CategoryDto> categories,
                                       EvidenceDto evidence, SubmissionDto submission, Instant briefingAt,
                                       String briefingVenue, String specialConditions, String miningEvidence) {
    }
}
