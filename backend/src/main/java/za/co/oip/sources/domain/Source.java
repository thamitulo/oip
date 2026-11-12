package za.co.oip.sources.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;
import za.co.oip.common.persistence.BaseEntity;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.sources.domain.SourceEnums.AccessMethod;
import za.co.oip.sources.domain.SourceEnums.AdapterType;
import za.co.oip.sources.domain.SourceEnums.IntegrationStatus;
import za.co.oip.sources.domain.SourceEnums.Reliability;
import za.co.oip.sources.domain.SourceEnums.RobotsPolicy;
import za.co.oip.sources.domain.SourceEnums.SourceType;
import za.co.oip.sources.domain.SourceEnums.TermsReviewStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** An entry in the source registry: a place where procurement opportunities are published. */
@Getter
@Setter
@Entity
@Table(name = "source_registry")
public class Source extends BaseEntity {

    @Column(nullable = false)
    private String name;
    @Column(name = "organisation_id")
    private UUID organisationId;
    @Column(name = "source_url")
    private String sourceUrl;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sector sector;
    @Column(nullable = false)
    private String country = "ZA";
    private String industry;
    @Enumerated(EnumType.STRING)
    @Column(name = "access_method", nullable = false)
    private AccessMethod accessMethod;
    @Enumerated(EnumType.STRING)
    @Column(name = "integration_status", nullable = false)
    private IntegrationStatus integrationStatus;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "authentication_required")
    private Boolean authenticationRequired;
    @Column(name = "publicly_accessible")
    private Boolean publiclyAccessible;
    @Enumerated(EnumType.STRING)
    @Column(name = "robots_policy", nullable = false)
    private RobotsPolicy robotsPolicy = RobotsPolicy.UNKNOWN;
    @Enumerated(EnumType.STRING)
    @Column(name = "terms_review_status", nullable = false)
    private TermsReviewStatus termsReviewStatus = TermsReviewStatus.NOT_REVIEWED;
    @Column(name = "licence_notes")
    private String licenceNotes;
    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute = 6;
    @Enumerated(EnumType.STRING)
    @Column(name = "adapter_type")
    private AdapterType adapterType;
    /** Adapter-specific configuration as a JSON document. */
    @ColumnTransformer(write = "?::jsonb")
    @Column(name = "parser_config", columnDefinition = "jsonb")
    private String parserConfig;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Reliability reliability = Reliability.UNKNOWN;
    @Column(nullable = false)
    private int priority = 100;
    @Column(name = "verification_notes")
    private String verificationNotes;
    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;
    @Column(name = "last_attempted_crawl_at")
    private Instant lastAttemptedCrawlAt;
    @Column(name = "last_successful_crawl_at")
    private Instant lastSuccessfulCrawlAt;

    /**
     * Reasons why automated collection is not currently permitted for this source. Empty when the
     * source may be crawled (ignoring the {@code enabled} flag). Mirrors the database constraint
     * {@code ck_source_enabled_requires_permitted_access}.
     */
    public List<String> crawlBlockers() {
        List<String> reasons = new ArrayList<>();
        if (!integrationStatus.isCrawlable()) {
            reasons.add("Integration status " + integrationStatus + " does not permit automated collection");
        }
        if (termsReviewStatus == TermsReviewStatus.PROHIBITED) {
            reasons.add("Terms of use prohibit automated collection");
        }
        if (robotsPolicy == RobotsPolicy.DISALLOWED) {
            reasons.add("robots.txt disallows collection");
        }
        if (adapterType == null) {
            reasons.add("No adapter configured");
        }
        if (sourceUrl == null || sourceUrl.isBlank()) {
            reasons.add("No source URL");
        }
        return reasons;
    }
}
