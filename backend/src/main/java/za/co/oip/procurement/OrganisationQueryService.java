package za.co.oip.procurement;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.common.api.PageResponse;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.procurement.api.OrganisationDtos.ChannelDto;
import za.co.oip.procurement.api.OrganisationDtos.MiningOperationDto;
import za.co.oip.procurement.api.OrganisationDtos.OrganisationDetailDto;
import za.co.oip.procurement.api.OrganisationDtos.OrganisationSummaryDto;
import za.co.oip.procurement.api.OrganisationDtos.SourceSummaryDto;
import za.co.oip.procurement.api.OrganisationDtos.SupplierPlatformDto;
import za.co.oip.procurement.domain.MiningCompany;
import za.co.oip.procurement.domain.MiningCompanyRepository;
import za.co.oip.procurement.domain.MiningOperationRepository;
import za.co.oip.procurement.domain.ProcurementChannelRepository;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.procurement.domain.ProcurementOrganisation;
import za.co.oip.procurement.domain.ProcurementOrganisationRepository;
import za.co.oip.procurement.domain.SupplierPlatform;
import za.co.oip.procurement.domain.SupplierPlatformRepository;
import za.co.oip.sources.domain.SourceRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrganisationQueryService {

    private final ProcurementOrganisationRepository organisationRepository;
    private final ProcurementChannelRepository channelRepository;
    private final SupplierPlatformRepository platformRepository;
    private final MiningCompanyRepository miningCompanyRepository;
    private final MiningOperationRepository operationRepository;
    private final SourceRepository sourceRepository;

    public OrganisationQueryService(ProcurementOrganisationRepository organisationRepository,
                                    ProcurementChannelRepository channelRepository,
                                    SupplierPlatformRepository platformRepository,
                                    MiningCompanyRepository miningCompanyRepository,
                                    MiningOperationRepository operationRepository,
                                    SourceRepository sourceRepository) {
        this.operationRepository = operationRepository;
        this.organisationRepository = organisationRepository;
        this.channelRepository = channelRepository;
        this.platformRepository = platformRepository;
        this.miningCompanyRepository = miningCompanyRepository;
        this.sourceRepository = sourceRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrganisationSummaryDto> search(Sector sector, String q, Pageable pageable) {
        String query = q == null || q.isBlank() ? null : q.trim();
        return PageResponse.of(organisationRepository.search(sector, query, pageable), OrganisationQueryService::summary);
    }

    @Transactional(readOnly = true)
    public OrganisationDetailDto get(UUID id) {
        ProcurementOrganisation org = organisationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Organisation", id));
        Map<UUID, String> platformNames = platformRepository.findAll().stream()
                .collect(Collectors.toMap(SupplierPlatform::getId, SupplierPlatform::getName));
        List<ChannelDto> channels = channelRepository.findByOrganisationIdOrderByChannelTypeAsc(id).stream()
                .map(c -> new ChannelDto(c.getId(), c.getChannelType(), c.getName(), c.getUrl(),
                        c.getSupplierPlatformId() == null ? null : platformNames.get(c.getSupplierPlatformId()),
                        c.getDescription(), c.getVerificationStatus(), c.getLastVerifiedAt(), c.getSupplierPlatformId()))
                .toList();
        List<SourceSummaryDto> sources = sourceRepository.findByOrganisationId(id).stream()
                .map(s -> new SourceSummaryDto(s.getId(), s.getName(), s.getSourceUrl(), s.getIntegrationStatus(),
                        s.isEnabled(), s.getLastSuccessfulCrawlAt(), s.getLastVerifiedAt(), s.getVerificationNotes()))
                .toList();
        MiningCompany mining = miningCompanyRepository.findByOrganisationId(id).orElse(null);
        List<MiningOperationDto> operations = mining == null ? List.of()
                : operationRepository.findByMiningCompanyIdOrderByNameAsc(mining.getId()).stream()
                .map(o -> new MiningOperationDto(o.getId(), o.getName(), o.getProvince(), o.getLocality(), o.getCommodity()))
                .toList();
        return new OrganisationDetailDto(summary(org), org.getNotes(), mining == null ? null : mining.getPrimaryCommodities(),
                channels, sources, operations);
    }

    @Transactional(readOnly = true)
    public List<SupplierPlatformDto> platforms() {
        return platformRepository.findAllByOrderByNameAsc().stream()
                .map(p -> new SupplierPlatformDto(p.getId(), p.getName(), p.getPlatformType(), p.getWebsite(),
                        p.getAccessNotes()))
                .toList();
    }

    static OrganisationSummaryDto summary(ProcurementOrganisation o) {
        return new OrganisationSummaryDto(o.getId(), o.getName(), o.getShortName(), o.getOrganisationType(),
                o.getSector(), o.getWebsite(), o.getWebsiteVerification(), o.getProvince());
    }
}
