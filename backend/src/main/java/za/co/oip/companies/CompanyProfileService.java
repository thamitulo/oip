package za.co.oip.companies;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.companies.api.CompanyProfileDto;
import za.co.oip.companies.domain.CompanyProfile;
import za.co.oip.companies.domain.CompanyProfileRepository;

import java.util.Map;
import java.util.UUID;

@Service
public class CompanyProfileService {

    private final CompanyProfileRepository repository;
    private final AuditService auditService;
    private final org.springframework.context.ApplicationEventPublisher events;

    /** Published after a company profile change is committed. */
    public record ProfileChangedEvent(UUID tenantId) {
    }

    public CompanyProfileService(CompanyProfileRepository repository, AuditService auditService,
                                 org.springframework.context.ApplicationEventPublisher events) {
        this.repository = repository;
        this.auditService = auditService;
        this.events = events;
    }

    /** Creates the initial (mostly empty) profile when a tenant registers. */
    @Transactional
    public CompanyProfile createInitialProfile(UUID tenantId, String legalName) {
        CompanyProfile profile = new CompanyProfile();
        profile.setTenantId(tenantId);
        profile.setLegalName(legalName);
        return repository.save(profile);
    }

    @Transactional(readOnly = true)
    public CompanyProfileDto get(UUID tenantId) {
        return CompanyProfileMapper.toDto(load(tenantId));
    }

    /** Loads the profile entity for internal consumers such as the matching engine. */
    @Transactional(readOnly = true)
    public CompanyProfile load(UUID tenantId) {
        return repository.findByTenantId(tenantId)
                .orElseThrow(() -> new NotFoundException("Company profile for tenant", tenantId));
    }

    @Transactional
    public CompanyProfileDto update(UUID tenantId, CompanyProfileDto dto) {
        CompanyProfile profile = load(tenantId);
        CompanyProfileMapper.apply(dto, profile);
        CompanyProfile saved = repository.saveAndFlush(profile);
        auditService.record(tenantId, "COMPANY_PROFILE_UPDATED", "CompanyProfile", saved.getId(),
                Map.of("legalName", saved.getLegalName()));
        events.publishEvent(new ProfileChangedEvent(tenantId));
        return CompanyProfileMapper.toDto(saved);
    }
}
