package za.co.oip.opportunities.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import za.co.oip.opportunities.domain.OpportunityEnums.DocumentType;

import java.time.Instant;
import java.util.UUID;

/** A document link published with an opportunity. Downloading/extraction is handled by the documents module. */
@Getter
@Setter
@Entity
@Table(name = "opportunity_documents")
public class OpportunityDocument {

    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "opportunity_id", nullable = false, updatable = false)
    private UUID opportunityId;
    private String title;
    @Column(nullable = false)
    private String url;
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private DocumentType documentType = DocumentType.OTHER;
    @Column(name = "file_format")
    private String fileFormat;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
    @Column(name = "removed_at")
    private Instant removedAt;
}
