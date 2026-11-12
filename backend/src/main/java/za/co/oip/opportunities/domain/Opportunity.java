package za.co.oip.opportunities.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;
import za.co.oip.opportunities.domain.OpportunityEnums.ProcurementCategory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A procurement opportunity as published by a source. Stored once, globally; tenants relate to it
 * through tenant-specific records and never modify it.
 */
@Getter
@Setter
@Entity
@Table(name = "opportunities")
public class Opportunity {

    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;
    @Column(name = "organisation_id")
    private UUID organisationId;
    @Column(name = "source_reference", nullable = false, updatable = false)
    private String sourceReference;
    @Column(name = "reference_number")
    private String referenceNumber;
    @Column(nullable = false)
    private String title;
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;
    @Enumerated(EnumType.STRING)
    @Column(name = "procurement_category", nullable = false)
    private ProcurementCategory procurementCategory = ProcurementCategory.NOT_SPECIFIED;
    @Column(name = "source_category_label")
    private String sourceCategoryLabel;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OpportunityStatus status = OpportunityStatus.UNKNOWN;
    @Column(name = "status_inferred", nullable = false)
    private boolean statusInferred;
    private String province;
    private String locality;
    @Column(name = "mine_site")
    private String mineSite;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "closing_at")
    private Instant closingAt;
    @Column(name = "closing_date")
    private LocalDate closingDate;
    @Column(name = "closing_time_specified", nullable = false)
    private boolean closingTimeSpecified;
    @Enumerated(EnumType.STRING)
    @Column(name = "closing_provenance")
    private za.co.oip.opportunities.domain.OpportunityEnums.Provenance closingProvenance;
    @Column(name = "closing_raw")
    private String closingRaw;
    @Column(name = "estimated_value")
    private BigDecimal estimatedValue;
    private String currency;
    @Column(name = "contract_duration")
    private String contractDuration;
    @Column(name = "briefing_details")
    private String briefingDetails;
    @Column(name = "briefing_compulsory")
    private Boolean briefingCompulsory;
    @Enumerated(EnumType.STRING)
    @Column(name = "submission_method", nullable = false)
    private za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod submissionMethod =
            za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod.NOT_SPECIFIED;
    @Column(name = "submission_channel")
    private String submissionChannel;
    @Column(name = "submission_address")
    private String submissionAddress;
    @Enumerated(EnumType.STRING)
    @Column(name = "submission_provenance")
    private za.co.oip.opportunities.domain.OpportunityEnums.Provenance submissionProvenance;
    @Column(name = "submission_evidence")
    private String submissionEvidence;
    @Column(name = "two_envelope")
    private Boolean twoEnvelope;
    @Column(name = "briefing_at")
    private Instant briefingAt;
    @Column(name = "briefing_venue")
    private String briefingVenue;
    @Column(name = "special_conditions")
    private String specialConditions;
    @Column(name = "mining_related", nullable = false)
    private boolean miningRelated;
    @Column(name = "mining_evidence")
    private String miningEvidence;
    @Column(name = "contact_name")
    private String contactName;
    @Column(name = "contact_email")
    private String contactEmail;
    @Column(name = "contact_phone")
    private String contactPhone;
    @Column(name = "source_url", nullable = false)
    private String sourceUrl;
    @Column(name = "evidence_url")
    private String evidenceUrl;
    @Column(name = "content_hash", nullable = false)
    private String contentHash;
    @Column(name = "duplicate_of_id")
    private UUID duplicateOfId;
    @Column(name = "first_discovered_at", nullable = false, updatable = false)
    private Instant firstDiscoveredAt;
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
    @Column(name = "last_checked_at", nullable = false)
    private Instant lastCheckedAt;
    @Column(name = "last_changed_at")
    private Instant lastChangedAt;
    @Column(name = "last_run_id")
    private UUID lastRunId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;
}
