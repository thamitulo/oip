package za.co.oip.procurement.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.ConflictException;
import za.co.oip.common.reference.Province;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.procurement.OrganisationQueryService;
import za.co.oip.procurement.domain.MiningCompany;
import za.co.oip.procurement.domain.MiningCompanyRepository;
import za.co.oip.procurement.domain.MiningOperation;
import za.co.oip.procurement.domain.MiningOperationRepository;
import za.co.oip.procurement.domain.ProcurementChannel;
import za.co.oip.procurement.domain.ProcurementChannelRepository;
import za.co.oip.procurement.domain.ProcurementEnums.ChannelType;
import za.co.oip.procurement.domain.ProcurementEnums.ChannelVerification;
import za.co.oip.procurement.domain.ProcurementEnums.OrganisationType;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.procurement.domain.ProcurementEnums.WebsiteVerification;
import za.co.oip.procurement.domain.ProcurementOrganisation;
import za.co.oip.procurement.domain.ProcurementOrganisationRepository;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Administration of organisations, their procurement channels and (for mining companies) their operations.
 * Platform administrators only; see SecurityConfig.
 */
@Tag(name = "Admin: organisations")
@RestController
@RequestMapping("/api/v1/admin/organisations")
public class OrganisationAdminController {

    private final ProcurementOrganisationRepository organisations;
    private final ProcurementChannelRepository channels;
    private final MiningCompanyRepository miningCompanies;
    private final MiningOperationRepository operations;
    private final OrganisationQueryService queries;
    private final AuditService audit;
    private final Clock clock;

    public OrganisationAdminController(ProcurementOrganisationRepository organisations, ProcurementChannelRepository channels,
                                       MiningCompanyRepository miningCompanies, MiningOperationRepository operations,
                                       OrganisationQueryService queries, AuditService audit, Clock clock) {
        this.operations = operations;
        this.organisations = organisations;
        this.channels = channels;
        this.miningCompanies = miningCompanies;
        this.queries = queries;
        this.audit = audit;
        this.clock = clock;
    }

    public record OrganisationRequest(@NotBlank @Size(max = 300) String name, @Size(max = 100) String shortName,
                                      @NotNull OrganisationType organisationType, @NotNull Sector sector,
                                      @Size(max = 500) @Pattern(regexp = "^(https?://.+)?$") String website,
                                      @NotNull WebsiteVerification websiteVerification, @Size(max = 40) String province,
                                      @Size(max = 5000) String notes, @Size(max = 500) String primaryCommodities) {
    }

    public record ChannelRequest(@NotNull ChannelType channelType, @NotBlank @Size(max = 200) String name,
                                 @Size(max = 1000) @Pattern(regexp = "^(https?://.+)?$") String url, UUID supplierPlatformId,
                                 @Size(max = 5000) String description, @NotNull ChannelVerification verificationStatus) {
    }

    public record OperationRequest(@NotBlank @Size(max = 200) String name, Province province,
                                   @Size(max = 200) String locality, @Size(max = 200) String commodity) {
    }

    @Operation(summary = "Create an organisation")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public OrganisationDtos.OrganisationDetailDto create(@Valid @RequestBody OrganisationRequest r) {
        if (organisations.findByNameIgnoreCase(r.name().trim()).isPresent()) {
            throw new ConflictException("An organisation with this name already exists");
        }
        ProcurementOrganisation o = new ProcurementOrganisation();
        apply(o, r);
        organisations.save(o);
        syncMining(o, r);
        audit.record(null, "ORGANISATION_CREATED", "Organisation", o.getId(), Map.of("name", o.getName()));
        return queries.get(o.getId());
    }

    @Operation(summary = "Update an organisation")
    @PutMapping("/{id}")
    @Transactional
    public OrganisationDtos.OrganisationDetailDto update(@PathVariable UUID id, @Valid @RequestBody OrganisationRequest r) {
        ProcurementOrganisation o = organisations.findById(id).orElseThrow(() -> new NotFoundException("Organisation", id));
        apply(o, r);
        organisations.save(o);
        syncMining(o, r);
        audit.record(null, "ORGANISATION_UPDATED", "Organisation", id, Map.of("name", o.getName()));
        return queries.get(id);
    }

    @Operation(summary = "Add a procurement channel to an organisation")
    @PostMapping("/{id}/channels")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public OrganisationDtos.OrganisationDetailDto addChannel(@PathVariable UUID id, @Valid @RequestBody ChannelRequest r) {
        organisations.findById(id).orElseThrow(() -> new NotFoundException("Organisation", id));
        ProcurementChannel c = new ProcurementChannel();
        c.setOrganisationId(id);
        c.setCreatedAt(clock.instant());
        applyChannel(c, r);
        channels.save(c);
        audit.record(null, "CHANNEL_CREATED", "ProcurementChannel", c.getId(), Map.of("name", c.getName()));
        return queries.get(id);
    }

    @Operation(summary = "Update a procurement channel")
    @PutMapping("/{id}/channels/{channelId}")
    @Transactional
    public OrganisationDtos.OrganisationDetailDto updateChannel(@PathVariable UUID id, @PathVariable UUID channelId,
                                                                @Valid @RequestBody ChannelRequest r) {
        ProcurementChannel c = channels.findById(channelId).filter(x -> x.getOrganisationId().equals(id))
                .orElseThrow(() -> new NotFoundException("Channel", channelId));
        applyChannel(c, r);
        channels.save(c);
        audit.record(null, "CHANNEL_UPDATED", "ProcurementChannel", channelId, Map.of("name", c.getName()));
        return queries.get(id);
    }

    @Operation(summary = "Delete a procurement channel")
    @DeleteMapping("/{id}/channels/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteChannel(@PathVariable UUID id, @PathVariable UUID channelId) {
        ProcurementChannel c = channels.findById(channelId).filter(x -> x.getOrganisationId().equals(id))
                .orElseThrow(() -> new NotFoundException("Channel", channelId));
        channels.delete(c);
        audit.record(null, "CHANNEL_DELETED", "ProcurementChannel", channelId, Map.of("name", c.getName()));
    }

    @Operation(summary = "Add a mining operation (mine, plant or site) to a mining-sector organisation")
    @PostMapping("/{id}/operations")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public OrganisationDtos.OrganisationDetailDto addOperation(@PathVariable UUID id, @Valid @RequestBody OperationRequest r) {
        ProcurementOrganisation o = organisations.findById(id).orElseThrow(() -> new NotFoundException("Organisation", id));
        if (o.getSector() != Sector.MINING) {
            throw new BusinessRuleException("Operations can only be recorded for mining-sector organisations");
        }
        MiningCompany company = miningCompanies.findByOrganisationId(id).orElseGet(() -> newMiningCompany(id));
        if (operations.existsByMiningCompanyIdAndNameIgnoreCase(company.getId(), r.name().trim())) {
            throw new ConflictException("This organisation already has an operation with this name");
        }
        MiningOperation op = new MiningOperation();
        op.setMiningCompanyId(company.getId());
        op.setCreatedAt(clock.instant());
        applyOperation(op, r);
        operations.save(op);
        audit.record(null, "MINING_OPERATION_CREATED", "MiningOperation", op.getId(), Map.of("name", op.getName()));
        return queries.get(id);
    }

    @Operation(summary = "Update a mining operation")
    @PutMapping("/{id}/operations/{operationId}")
    @Transactional
    public OrganisationDtos.OrganisationDetailDto updateOperation(@PathVariable UUID id, @PathVariable UUID operationId,
                                                                  @Valid @RequestBody OperationRequest r) {
        MiningOperation op = findOperation(id, operationId);
        if (!op.getName().equalsIgnoreCase(r.name().trim())
                && operations.existsByMiningCompanyIdAndNameIgnoreCase(op.getMiningCompanyId(), r.name().trim())) {
            throw new ConflictException("This organisation already has an operation with this name");
        }
        applyOperation(op, r);
        operations.save(op);
        audit.record(null, "MINING_OPERATION_UPDATED", "MiningOperation", operationId, Map.of("name", op.getName()));
        return queries.get(id);
    }

    @Operation(summary = "Delete a mining operation")
    @DeleteMapping("/{id}/operations/{operationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteOperation(@PathVariable UUID id, @PathVariable UUID operationId) {
        MiningOperation op = findOperation(id, operationId);
        operations.delete(op);
        audit.record(null, "MINING_OPERATION_DELETED", "MiningOperation", operationId, Map.of("name", op.getName()));
    }

    /** The operation, provided it belongs to this organisation's mining company. */
    private MiningOperation findOperation(UUID orgId, UUID operationId) {
        UUID companyId = miningCompanies.findByOrganisationId(orgId).map(MiningCompany::getId)
                .orElseThrow(() -> new NotFoundException("Operation", operationId));
        return operations.findById(operationId).filter(x -> x.getMiningCompanyId().equals(companyId))
                .orElseThrow(() -> new NotFoundException("Operation", operationId));
    }

    private void applyOperation(MiningOperation op, OperationRequest r) {
        op.setName(r.name().trim());
        op.setProvince(r.province() == null ? null : r.province().name());
        op.setLocality(blank(r.locality()));
        op.setCommodity(blank(r.commodity()));
        op.setUpdatedAt(clock.instant());
    }

    private MiningCompany newMiningCompany(UUID orgId) {
        MiningCompany n = new MiningCompany();
        n.setOrganisationId(orgId);
        n.setCreatedAt(clock.instant());
        n.setUpdatedAt(clock.instant());
        return miningCompanies.save(n);
    }

    private void apply(ProcurementOrganisation o, OrganisationRequest r) {
        o.setName(r.name().trim());
        o.setShortName(blank(r.shortName()));
        o.setOrganisationType(r.organisationType());
        o.setSector(r.sector());
        o.setWebsite(blank(r.website()));
        if (o.getWebsiteVerification() != r.websiteVerification()) {
            o.setWebsiteVerification(r.websiteVerification());
            o.setWebsiteVerifiedAt(r.websiteVerification() == WebsiteVerification.UNVERIFIED ? null : clock.instant());
        }
        o.setProvince(blank(r.province()));
        o.setNotes(blank(r.notes()));
    }

    private void syncMining(ProcurementOrganisation o, OrganisationRequest r) {
        if (o.getSector() != Sector.MINING) return;
        MiningCompany m = miningCompanies.findByOrganisationId(o.getId()).orElseGet(() -> newMiningCompany(o.getId()));
        m.setPrimaryCommodities(blank(r.primaryCommodities()));
        m.setUpdatedAt(clock.instant());
        miningCompanies.save(m);
    }

    private void applyChannel(ProcurementChannel c, ChannelRequest r) {
        c.setChannelType(r.channelType());
        c.setName(r.name().trim());
        c.setUrl(blank(r.url()));
        c.setSupplierPlatformId(r.supplierPlatformId());
        c.setDescription(blank(r.description()));
        if (c.getVerificationStatus() != r.verificationStatus()) {
            c.setVerificationStatus(r.verificationStatus());
            c.setLastVerifiedAt(r.verificationStatus() == ChannelVerification.PENDING_VERIFICATION ? null : clock.instant());
        }
        c.setUpdatedAt(clock.instant());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
