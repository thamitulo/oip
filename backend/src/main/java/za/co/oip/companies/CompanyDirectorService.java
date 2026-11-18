package za.co.oip.companies;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.companies.api.DirectorDtos.DirectorDto;
import za.co.oip.companies.api.DirectorDtos.DirectorRequest;
import za.co.oip.companies.domain.CompanyDirector;
import za.co.oip.companies.domain.CompanyDirector.IdType;
import za.co.oip.companies.domain.CompanyDirectorRepository;
import za.co.oip.companies.domain.CompanyProfile;
import za.co.oip.companies.domain.CompanyProfileRepository;
import za.co.oip.security.FieldEncryptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Directors, members, trustees and shareholders of a tenant's company. Identity numbers are encrypted at rest and
 * only ever returned masked through the API; the form filler reads them through {@link #forForms}.
 */
@Service
public class CompanyDirectorService {

    /**
     * A director as needed to fill a form. {@code idNumber} is the decrypted number and is null unless the caller
     * asked for it (only when generating the final PDF, for users allowed to see it); {@code idNumberMasked} is safe
     * to show and to store in drafts.
     */
    public record DirectorForForm(UUID id, String fullName, CompanyDirector.Role role, String idNumber, String idNumberMasked,
                                  String incomeTaxNumber, BigDecimal shareholdingPercentage, boolean inServiceOfState) {
    }

    private final CompanyDirectorRepository directors;
    private final CompanyProfileRepository profiles;
    private final FieldEncryptor encryptor;
    private final AuditService audit;
    private final Clock clock;

    public CompanyDirectorService(CompanyDirectorRepository directors, CompanyProfileRepository profiles,
                                  FieldEncryptor encryptor, AuditService audit, Clock clock) {
        this.directors = directors;
        this.profiles = profiles;
        this.encryptor = encryptor;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DirectorDto> list(UUID tenantId) {
        return directors.findByCompanyProfileIdOrderByCreatedAtAsc(profile(tenantId).getId()).stream()
                .map(CompanyDirectorService::toDto).toList();
    }

    @Transactional
    public DirectorDto create(UUID tenantId, DirectorRequest r) {
        CompanyDirector d = new CompanyDirector();
        d.setCompanyProfileId(profile(tenantId).getId());
        d.setCreatedAt(clock.instant());
        apply(d, r);
        directors.save(d);
        audit.record(tenantId, "COMPANY_DIRECTOR_CREATED", "CompanyDirector", d.getId(), Map.of("name", d.getFullName()));
        return toDto(d);
    }

    @Transactional
    public DirectorDto update(UUID tenantId, UUID id, DirectorRequest r) {
        CompanyDirector d = find(tenantId, id);
        apply(d, r);
        directors.save(d);
        audit.record(tenantId, "COMPANY_DIRECTOR_UPDATED", "CompanyDirector", id, Map.of("name", d.getFullName()));
        return toDto(d);
    }

    @Transactional
    public void delete(UUID tenantId, UUID id) {
        CompanyDirector d = find(tenantId, id);
        directors.delete(d);
        audit.record(tenantId, "COMPANY_DIRECTOR_DELETED", "CompanyDirector", id, Map.of("name", d.getFullName()));
    }

    /** Directors for form filling. Identity numbers are decrypted only when {@code includeIdNumbers} is true. */
    @Transactional(readOnly = true)
    public List<DirectorForForm> forForms(UUID tenantId, boolean includeIdNumbers) {
        return directors.findByCompanyProfileIdOrderByCreatedAtAsc(profile(tenantId).getId()).stream()
                .map(d -> new DirectorForForm(d.getId(), d.getFullName(), d.getRole(),
                        includeIdNumbers && d.getIdNumberEncrypted() != null ? encryptor.decrypt(d.getIdNumberEncrypted()) : null,
                        toDto(d).idNumberMasked(), d.getIncomeTaxNumber(), d.getShareholdingPercentage(), d.isInServiceOfState()))
                .toList();
    }

    private void apply(CompanyDirector d, DirectorRequest r) {
        d.setFullName(r.fullName().trim());
        d.setRole(r.role());
        d.setIncomeTaxNumber(blank(r.incomeTaxNumber()));
        d.setShareholdingPercentage(r.shareholdingPercentage());
        d.setInServiceOfState(Boolean.TRUE.equals(r.inServiceOfState()));
        d.setUpdatedAt(clock.instant());
        // null keeps the stored number (the client never sees it); an empty string removes it.
        if (r.idNumber() != null) {
            String id = r.idNumber().replaceAll("\\s", "");
            if (id.isEmpty()) {
                d.setIdType(null);
                d.setIdNumberEncrypted(null);
                d.setIdNumberLast4(null);
            } else {
                IdType type = r.idType() == null ? IdType.SA_ID : r.idType();
                if (type == IdType.SA_ID && !SaIdNumber.isValid(id)) {
                    throw new BusinessRuleException("This is not a valid South African identity number");
                }
                if (type == IdType.PASSPORT && !id.matches("[A-Za-z0-9]{5,20}")) {
                    throw new BusinessRuleException("A passport number has 5 to 20 letters and digits");
                }
                d.setIdType(type);
                d.setIdNumberEncrypted(encryptor.encrypt(id));
                d.setIdNumberLast4(id.substring(id.length() - 4));
            }
        }
    }

    private CompanyDirector find(UUID tenantId, UUID id) {
        UUID profileId = profile(tenantId).getId();
        return directors.findById(id).filter(d -> d.getCompanyProfileId().equals(profileId))
                .orElseThrow(() -> new NotFoundException("Director", id));
    }

    private CompanyProfile profile(UUID tenantId) {
        return profiles.findByTenantId(tenantId).orElseThrow(() -> new NotFoundException("Company profile for tenant", tenantId));
    }

    static DirectorDto toDto(CompanyDirector d) {
        String masked = d.getIdNumberLast4() == null ? null : "•".repeat(d.getIdType() == IdType.SA_ID ? 9 : 4) + d.getIdNumberLast4();
        return new DirectorDto(d.getId(), d.getFullName(), d.getRole(), d.getIdType(), masked, d.getIncomeTaxNumber(),
                d.getShareholdingPercentage(), d.isInServiceOfState());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
