package za.co.oip.sources.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.sources.domain.SourceEnums.AccessMethod;
import za.co.oip.sources.domain.SourceEnums.AdapterType;
import za.co.oip.sources.domain.SourceEnums.IntegrationStatus;
import za.co.oip.sources.domain.SourceEnums.Reliability;
import za.co.oip.sources.domain.SourceEnums.RobotsPolicy;
import za.co.oip.sources.domain.SourceEnums.SourceType;
import za.co.oip.sources.domain.SourceEnums.TermsReviewStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SourceDtos {

    private SourceDtos() {
    }

    public record SourceDto(UUID id, String name, UUID organisationId, String organisationName, String sourceUrl,
                            SourceType sourceType, Sector sector, String country, String industry,
                            AccessMethod accessMethod, IntegrationStatus integrationStatus, boolean enabled,
                            Boolean authenticationRequired, Boolean publiclyAccessible, RobotsPolicy robotsPolicy,
                            TermsReviewStatus termsReviewStatus, String licenceNotes, int rateLimitPerMinute,
                            AdapterType adapterType, String parserConfig, Reliability reliability, int priority,
                            String verificationNotes, Instant lastVerifiedAt, Instant lastAttemptedCrawlAt,
                            Instant lastSuccessfulCrawlAt, Integer crawlIntervalMinutes, Instant nextCrawlAt,
                            Integer consecutiveFailures, List<String> crawlBlockers, Instant updatedAt) {
    }

    /** Create or full update of a source. */
    public record SourceRequest(@NotBlank @Size(max = 300) String name,
                                UUID organisationId,
                                @Size(max = 1000) @Pattern(regexp = "^(https?://.+)?$", message = "must be an http(s) URL")
                                String sourceUrl,
                                @NotNull SourceType sourceType,
                                @NotNull Sector sector,
                                @Size(max = 100) String industry,
                                @NotNull AccessMethod accessMethod,
                                @NotNull IntegrationStatus integrationStatus,
                                Boolean enabled,
                                Boolean authenticationRequired,
                                Boolean publiclyAccessible,
                                @NotNull RobotsPolicy robotsPolicy,
                                @NotNull TermsReviewStatus termsReviewStatus,
                                @Size(max = 5000) String licenceNotes,
                                @NotNull @Min(1) @Max(120) Integer rateLimitPerMinute,
                                AdapterType adapterType,
                                @Size(max = 20_000) String parserConfig,
                                @NotNull Reliability reliability,
                                @NotNull @Min(1) @Max(1000) Integer priority,
                                @Size(max = 5000) String verificationNotes,
                                @Min(15) @Max(10080) Integer crawlIntervalMinutes,
                                Boolean markVerified) {

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }

        public boolean shouldMarkVerified() {
            return Boolean.TRUE.equals(markVerified);
        }
    }
}
