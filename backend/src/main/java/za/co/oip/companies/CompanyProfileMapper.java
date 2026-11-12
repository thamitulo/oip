package za.co.oip.companies;

import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.reference.Province;
import za.co.oip.companies.api.CompanyProfileDto;
import za.co.oip.companies.api.CompanyProfileDto.CapabilityDto;
import za.co.oip.companies.api.CompanyProfileDto.CertificationDto;
import za.co.oip.companies.api.CompanyProfileDto.ReferenceDto;
import za.co.oip.companies.api.CompanyProfileDto.RegistrationDto;
import za.co.oip.companies.domain.CompanyEnums.VerificationStatus;
import za.co.oip.companies.domain.CompanyProfile;
import za.co.oip.companies.domain.ProfileItems;
import za.co.oip.companies.domain.ProfileItems.NamedItem;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Maps between {@link CompanyProfile} and {@link CompanyProfileDto}. */
final class CompanyProfileMapper {

    static final String NATIONAL_REGION = "NATIONAL";

    private CompanyProfileMapper() {
    }

    static CompanyProfileDto toDto(CompanyProfile p) {
        return new CompanyProfileDto(p.getId(), p.getUpdatedAt(), p.getLegalName(), p.getTradingName(),
                p.getRegistrationNumber(), p.getVatNumber(), p.getWebsite(), p.getDescription(), p.getProvince(),
                p.getCity(), p.getBusinessSize(), p.getAnnualTurnoverBand(), p.getBbbeeLevel(),
                p.getBlackOwnershipPercentage(), p.getEnterpriseClassification(), p.getCidbGrading(),
                p.getMiningSectorExperience(), p.getGovernmentSectorExperience(), p.getContactName(),
                p.getContactEmail(), p.getContactPhone(),
                names(p.getIndustries()), names(p.getServices()), names(p.getProducts()), names(p.getCommodities()),
                names(p.getRegions()),
                p.getCertifications().stream().map(c -> new CertificationDto(c.getName(), c.getIssuingBody(),
                        c.getCertificateNumber(), c.getValidUntil(), c.getVerificationStatus())).toList(),
                p.getRegistrations().stream().map(r -> new RegistrationDto(r.getRegistrationType(),
                        r.getRegistrationBody(), r.getRegistrationNumber(), r.getValidUntil(),
                        r.getVerificationStatus())).toList(),
                p.getCapabilities().stream().map(c -> new CapabilityDto(c.getName(), c.getDescription(),
                        c.getYearsExperience())).toList(),
                p.getReferences().stream().map(r -> new ReferenceDto(r.getClientName(), r.getProjectTitle(),
                        r.getDescription(), r.getSector(), r.getContractValue(), r.getYearCompleted())).toList());
    }

    static void apply(CompanyProfileDto dto, CompanyProfile p) {
        p.setLegalName(dto.legalName().trim());
        p.setTradingName(trim(dto.tradingName()));
        p.setRegistrationNumber(trim(dto.registrationNumber()));
        p.setVatNumber(trim(dto.vatNumber()));
        p.setWebsite(trim(dto.website()));
        p.setDescription(trim(dto.description()));
        p.setProvince(dto.province());
        p.setCity(trim(dto.city()));
        p.setBusinessSize(dto.businessSize());
        p.setAnnualTurnoverBand(trim(dto.annualTurnoverBand()));
        p.setBbbeeLevel(dto.bbbeeLevel());
        p.setBlackOwnershipPercentage(dto.blackOwnershipPercentage());
        p.setEnterpriseClassification(dto.enterpriseClassification());
        p.setCidbGrading(trim(dto.cidbGrading()));
        p.setMiningSectorExperience(trim(dto.miningSectorExperience()));
        p.setGovernmentSectorExperience(trim(dto.governmentSectorExperience()));
        p.setContactName(trim(dto.contactName()));
        p.setContactEmail(trim(dto.contactEmail()));
        p.setContactPhone(trim(dto.contactPhone()));

        p.replace(p.getIndustries(), named(dto.industries(), ProfileItems.Industry::new));
        p.replace(p.getServices(), named(dto.services(), ProfileItems.ServiceOffering::new));
        p.replace(p.getProducts(), named(dto.products(), ProfileItems.Product::new));
        p.replace(p.getCommodities(), named(dto.commodities(), ProfileItems.Commodity::new));
        p.replace(p.getRegions(), named(normaliseRegions(dto.operatingRegions()), ProfileItems.Region::new));

        p.replace(p.getCertifications(), safe(dto.certifications()).stream().map(c -> {
            var e = new ProfileItems.Certification();
            e.setName(c.name().trim());
            e.setIssuingBody(trim(c.issuingBody()));
            e.setCertificateNumber(trim(c.certificateNumber()));
            e.setValidUntil(c.validUntil());
            e.setVerificationStatus(userSettableStatus(c.verificationStatus()));
            return e;
        }).toList());
        p.replace(p.getRegistrations(), safe(dto.registrations()).stream().map(r -> {
            var e = new ProfileItems.Registration();
            e.setRegistrationType(r.registrationType());
            e.setRegistrationBody(r.registrationBody().trim());
            e.setRegistrationNumber(trim(r.registrationNumber()));
            e.setValidUntil(r.validUntil());
            e.setVerificationStatus(userSettableStatus(r.verificationStatus()));
            return e;
        }).toList());
        p.replace(p.getCapabilities(), safe(dto.capabilities()).stream().map(c -> {
            var e = new ProfileItems.Capability();
            e.setName(c.name().trim());
            e.setDescription(trim(c.description()));
            e.setYearsExperience(c.yearsExperience());
            return e;
        }).toList());
        p.replace(p.getReferences(), safe(dto.references()).stream().map(r -> {
            var e = new ProfileItems.Reference();
            e.setClientName(r.clientName().trim());
            e.setProjectTitle(r.projectTitle().trim());
            e.setDescription(trim(r.description()));
            e.setSector(r.sector());
            e.setContractValue(r.contractValue());
            e.setYearCompleted(r.yearCompleted());
            return e;
        }).toList());
    }

    /**
     * Users may declare a claim or state that a supporting document was provided; they may not mark a
     * claim as VERIFIED. Unknown/absent values default to SELF_DECLARED.
     */
    private static VerificationStatus userSettableStatus(VerificationStatus requested) {
        if (requested == VerificationStatus.DOCUMENT_PROVIDED || requested == VerificationStatus.EXPIRED) {
            return requested;
        }
        return VerificationStatus.SELF_DECLARED;
    }

    private static List<String> normaliseRegions(List<String> regions) {
        return safe(regions).stream().map(r -> {
            if (NATIONAL_REGION.equalsIgnoreCase(r.trim())) {
                return NATIONAL_REGION;
            }
            return Province.parse(r).map(Enum::name)
                    .orElseThrow(() -> new BusinessRuleException("Unknown operating region: " + r));
        }).toList();
    }

    /** De-duplicates case-insensitively, preserving first spelling and order. */
    private static <T extends NamedItem> List<T> named(List<String> values, Supplier<T> factory) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String v : safe(values)) {
            String t = v.trim();
            unique.putIfAbsent(t.toLowerCase(), t);
        }
        return unique.values().stream().map(n -> {
            T item = factory.get();
            item.setName(n);
            return item;
        }).toList();
    }

    private static List<String> names(Collection<? extends NamedItem> items) {
        return items.stream().map(NamedItem::getName).toList();
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
