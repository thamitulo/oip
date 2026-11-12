package za.co.oip.opportunities.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A classification of an opportunity with its provenance (SOURCE, RULE, AI or HUMAN). */
@Getter
@Setter
@Entity
@Table(name = "opportunity_categories")
public class OpportunityCategory {

    public enum CategoryType { PROCUREMENT_CATEGORY, INDUSTRY, COMMODITY, SERVICE, PRODUCT }

    public enum Provenance { SOURCE, RULE, AI, HUMAN }

    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "opportunity_id", nullable = false)
    private UUID opportunityId;
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "category_type", nullable = false)
    private CategoryType categoryType;
    @Column(nullable = false)
    private String code;
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    private Provenance provenance;
    private BigDecimal confidence;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
