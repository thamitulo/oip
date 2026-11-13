package za.co.oip.procurement.api;

import za.co.oip.procurement.domain.ProcurementEnums.ChannelType;
import za.co.oip.procurement.domain.ProcurementEnums.ChannelVerification;
import za.co.oip.procurement.domain.ProcurementEnums.OrganisationType;
import za.co.oip.procurement.domain.ProcurementEnums.PlatformType;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.procurement.domain.ProcurementEnums.WebsiteVerification;
import za.co.oip.sources.domain.SourceEnums.IntegrationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrganisationDtos {

    private OrganisationDtos() {
    }

    public record OrganisationSummaryDto(UUID id, String name, String shortName, OrganisationType organisationType,
                                         Sector sector, String website, WebsiteVerification websiteVerification,
                                         String province) {
    }

    public record ChannelDto(UUID id, ChannelType channelType, String name, String url, String supplierPlatform,
                             String description, ChannelVerification verificationStatus, Instant lastVerifiedAt,
                             UUID supplierPlatformId) {
    }

    /** Public view of a source: no parser internals. */
    public record SourceSummaryDto(UUID id, String name, String sourceUrl, IntegrationStatus integrationStatus,
                                   boolean enabled, Instant lastSuccessfulCrawlAt, Instant lastVerifiedAt,
                                   String verificationNotes) {
    }

    public record OrganisationDetailDto(OrganisationSummaryDto organisation, String notes, String primaryCommodities,
                                        List<ChannelDto> channels, List<SourceSummaryDto> sources) {
    }

    public record SupplierPlatformDto(UUID id, String name, PlatformType platformType, String website,
                                      String accessNotes) {
    }
}
