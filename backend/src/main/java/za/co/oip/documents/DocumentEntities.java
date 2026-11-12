package za.co.oip.documents;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import za.co.oip.opportunities.domain.OpportunityEnums.Provenance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Persistent records of the document pipeline. */
public final class DocumentEntities {

    private DocumentEntities() {
    }

    public enum ProcessingStatus { PENDING, EXTRACTED, FAILED, SKIPPED }

    /** A downloaded opportunity document with its extracted text. */
    @Getter
    @Setter
    @Entity(name = "SourceDocument")
    @Table(name = "source_documents")
    public static class SourceDocument {
        @Id
        private UUID id = UUID.randomUUID();
        @Column(name = "opportunity_document_id", nullable = false, unique = true)
        private UUID opportunityDocumentId;
        @Column(name = "opportunity_id", nullable = false)
        private UUID opportunityId;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private ProcessingStatus status = ProcessingStatus.PENDING;
        @Column(name = "content_type")
        private String contentType;
        @Column(name = "size_bytes")
        private Long sizeBytes;
        @Column(name = "checksum_sha256")
        private String checksumSha256;
        @Column(name = "storage_key")
        private String storageKey;
        @Column(name = "page_count")
        private Integer pageCount;
        @Column(name = "text_content")
        private String textContent;
        @Column(name = "text_truncated", nullable = false)
        private boolean textTruncated;
        private String error;
        @Column(nullable = false)
        private int attempts;
        @Column(name = "next_attempt_at")
        private Instant nextAttemptAt;
        @Column(name = "downloaded_at")
        private Instant downloadedAt;
        @Column(name = "extracted_at")
        private Instant extractedAt;
        @Column(name = "original_deleted_at")
        private Instant originalDeletedAt;
        @Column(name = "created_at", nullable = false)
        private Instant createdAt;
        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;
    }

    /** A requirement of an opportunity with its provenance and evidence. */
    @Getter
    @Setter
    @Entity(name = "OpportunityRequirement")
    @Table(name = "opportunity_requirements")
    public static class OpportunityRequirement {
        @Id
        private UUID id = UUID.randomUUID();
        @Column(name = "opportunity_id", nullable = false)
        private UUID opportunityId;
        @Column(name = "requirement_type", nullable = false)
        private String requirementType;
        private String value;
        private Boolean mandatory;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private Provenance provenance;
        private BigDecimal confidence;
        private String evidence;
        @Column(name = "source_document_id")
        private UUID sourceDocumentId;
        @Column(name = "created_at", nullable = false)
        private Instant createdAt;
    }
}
