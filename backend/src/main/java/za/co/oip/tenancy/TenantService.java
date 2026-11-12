package za.co.oip.tenancy;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.companies.CompanyProfileService;
import za.co.oip.subscriptions.SubscriptionService;
import za.co.oip.tenancy.api.TenantDtos.TenantDto;
import za.co.oip.tenancy.api.TenantDtos.UpdateTenantRequest;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class TenantService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SLUG_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

    private final TenantRepository tenantRepository;
    private final TenantUserRepository tenantUserRepository;
    private final CompanyProfileService companyProfileService;
    private final SubscriptionService subscriptionService;
    private final AuditService auditService;

    public TenantService(TenantRepository tenantRepository, TenantUserRepository tenantUserRepository,
                         CompanyProfileService companyProfileService, SubscriptionService subscriptionService,
                         AuditService auditService) {
        this.tenantRepository = tenantRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.companyProfileService = companyProfileService;
        this.subscriptionService = subscriptionService;
        this.auditService = auditService;
    }

    /** Creates a tenant with its owner membership, initial company profile and default subscription. */
    @Transactional
    public Tenant createTenant(String companyName, UUID ownerUserId) {
        Tenant tenant = new Tenant();
        tenant.setName(companyName.trim());
        tenant.setSlug(uniqueSlug(companyName));
        tenantRepository.save(tenant);

        addMember(tenant.getId(), ownerUserId, TenantRole.TENANT_OWNER);
        companyProfileService.createInitialProfile(tenant.getId(), tenant.getName());
        subscriptionService.startDefaultSubscription(tenant.getId());
        auditService.recordAs(ownerUserId, tenant.getId(), "TENANT_CREATED", "Tenant", tenant.getId());
        return tenant;
    }

    @Transactional
    public TenantUser addMember(UUID tenantId, UUID userId, TenantRole role) {
        TenantUser membership = new TenantUser();
        membership.setTenantId(tenantId);
        membership.setUserId(userId);
        membership.setRole(role);
        return tenantUserRepository.save(membership);
    }

    @Transactional(readOnly = true)
    public Tenant get(UUID tenantId) {
        return tenantRepository.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
    }

    @Transactional(readOnly = true)
    public TenantDto getDto(UUID tenantId) {
        return toDto(get(tenantId));
    }

    @Transactional
    public TenantDto update(UUID tenantId, UpdateTenantRequest request) {
        Tenant tenant = get(tenantId);
        try {
            ZoneId.of(request.timezone());
        } catch (DateTimeException e) {
            throw new BusinessRuleException("Unknown timezone: " + request.timezone());
        }
        tenant.setName(request.name().trim());
        tenant.setTimezone(request.timezone());
        tenantRepository.saveAndFlush(tenant);
        auditService.record(tenantId, "TENANT_UPDATED", "Tenant", tenantId, Map.of("name", tenant.getName()));
        return toDto(tenant);
    }

    private TenantDto toDto(Tenant t) {
        return new TenantDto(t.getId(), t.getName(), t.getSlug(), t.getStatus(), t.getTimezone(), t.getCreatedAt());
    }

    private String uniqueSlug(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) {
            base = "tenant";
        }
        base = base.length() > 60 ? base.substring(0, 60) : base;
        String slug;
        do {
            StringBuilder suffix = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                suffix.append(SLUG_ALPHABET.charAt(RANDOM.nextInt(SLUG_ALPHABET.length())));
            }
            slug = base + "-" + suffix;
        } while (tenantRepository.existsBySlug(slug));
        return slug;
    }
}
