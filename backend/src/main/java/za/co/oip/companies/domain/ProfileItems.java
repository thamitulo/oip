package za.co.oip.companies.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import za.co.oip.companies.domain.CompanyEnums.ReferenceSector;
import za.co.oip.companies.domain.CompanyEnums.RegistrationType;
import za.co.oip.companies.domain.CompanyEnums.VerificationStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Entities that belong to a {@link CompanyProfile}. */
public final class ProfileItems {

    private ProfileItems() {
    }

    /** A single named classification value (industry, service, product, commodity or region). */
    @Getter
    @Setter
    @MappedSuperclass
    public abstract static class NamedItem extends ProfileItem {
        @Column(nullable = false)
        private String name;
    }

    @Entity(name = "CompanyIndustry")
    @Table(name = "company_categories")
    public static class Industry extends NamedItem {
    }

    @Entity(name = "CompanyServiceOffering")
    @Table(name = "company_services")
    public static class ServiceOffering extends NamedItem {
    }

    @Entity(name = "CompanyProduct")
    @Table(name = "company_products")
    public static class Product extends NamedItem {
    }

    @Entity(name = "CompanyCommodity")
    @Table(name = "company_commodities")
    public static class Commodity extends NamedItem {
    }

    @Entity(name = "CompanyRegion")
    @Table(name = "company_regions")
    public static class Region extends NamedItem {
    }

    @Getter
    @Setter
    @Entity(name = "CompanyCertification")
    @Table(name = "company_certifications")
    public static class Certification extends ProfileItem {
        @Column(nullable = false)
        private String name;
        @Column(name = "issuing_body")
        private String issuingBody;
        @Column(name = "certificate_number")
        private String certificateNumber;
        @Column(name = "valid_until")
        private LocalDate validUntil;
        @Enumerated(EnumType.STRING)
        @Column(name = "verification_status", nullable = false)
        private VerificationStatus verificationStatus = VerificationStatus.SELF_DECLARED;
    }

    @Getter
    @Setter
    @Entity(name = "CompanyRegistration")
    @Table(name = "company_registrations")
    public static class Registration extends ProfileItem {
        @Enumerated(EnumType.STRING)
        @Column(name = "registration_type", nullable = false)
        private RegistrationType registrationType;
        @Column(name = "registration_body", nullable = false)
        private String registrationBody;
        @Column(name = "registration_number")
        private String registrationNumber;
        @Column(name = "valid_until")
        private LocalDate validUntil;
        @Enumerated(EnumType.STRING)
        @Column(name = "verification_status", nullable = false)
        private VerificationStatus verificationStatus = VerificationStatus.SELF_DECLARED;
    }

    @Getter
    @Setter
    @Entity(name = "CompanyCapability")
    @Table(name = "company_capabilities")
    public static class Capability extends ProfileItem {
        @Column(nullable = false)
        private String name;
        private String description;
        @Column(name = "years_experience")
        private Integer yearsExperience;
    }

    @Getter
    @Setter
    @Entity(name = "CompanyReference")
    @Table(name = "company_references")
    public static class Reference extends ProfileItem {
        @Column(name = "client_name", nullable = false)
        private String clientName;
        @Column(name = "project_title", nullable = false)
        private String projectTitle;
        private String description;
        @Enumerated(EnumType.STRING)
        private ReferenceSector sector;
        @Column(name = "contract_value")
        private BigDecimal contractValue;
        @Column(name = "year_completed")
        private Integer yearCompleted;
    }
}
