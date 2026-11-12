package za.co.oip.companies.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import za.co.oip.common.persistence.BaseEntity;
import za.co.oip.common.reference.Province;
import za.co.oip.companies.domain.CompanyEnums.BbbeeLevel;
import za.co.oip.companies.domain.CompanyEnums.BusinessSize;
import za.co.oip.companies.domain.CompanyEnums.EnterpriseClassification;
import za.co.oip.companies.domain.ProfileItems.Capability;
import za.co.oip.companies.domain.ProfileItems.Certification;
import za.co.oip.companies.domain.ProfileItems.Commodity;
import za.co.oip.companies.domain.ProfileItems.Industry;
import za.co.oip.companies.domain.ProfileItems.Product;
import za.co.oip.companies.domain.ProfileItems.Reference;
import za.co.oip.companies.domain.ProfileItems.Region;
import za.co.oip.companies.domain.ProfileItems.Registration;
import za.co.oip.companies.domain.ProfileItems.ServiceOffering;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The business profile of a tenant. It drives opportunity matching. One profile per tenant. */
@Getter
@Setter
@Entity
@Table(name = "company_profiles")
public class CompanyProfile extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false, unique = true)
    private UUID tenantId;

    @Column(name = "legal_name", nullable = false)
    private String legalName;
    @Column(name = "trading_name")
    private String tradingName;
    @Column(name = "registration_number")
    private String registrationNumber;
    @Column(name = "vat_number")
    private String vatNumber;
    private String website;
    private String description;
    @Enumerated(EnumType.STRING)
    private Province province;
    private String city;
    @Enumerated(EnumType.STRING)
    @Column(name = "business_size")
    private BusinessSize businessSize;
    @Column(name = "annual_turnover_band")
    private String annualTurnoverBand;
    @Enumerated(EnumType.STRING)
    @Column(name = "bbbee_level")
    private BbbeeLevel bbbeeLevel;
    @Column(name = "black_ownership_percentage")
    private BigDecimal blackOwnershipPercentage;
    @Enumerated(EnumType.STRING)
    @Column(name = "enterprise_classification")
    private EnterpriseClassification enterpriseClassification;
    @Column(name = "cidb_grading")
    private String cidbGrading;
    @Column(name = "mining_sector_experience")
    private String miningSectorExperience;
    @Column(name = "government_sector_experience")
    private String governmentSectorExperience;
    @Column(name = "contact_name")
    private String contactName;
    @Column(name = "contact_email")
    private String contactEmail;
    @Column(name = "contact_phone")
    private String contactPhone;

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Industry> industries = new ArrayList<>();
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ServiceOffering> services = new ArrayList<>();
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Product> products = new ArrayList<>();
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Commodity> commodities = new ArrayList<>();
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Region> regions = new ArrayList<>();
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Certification> certifications = new ArrayList<>();
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Registration> registrations = new ArrayList<>();
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Capability> capabilities = new ArrayList<>();
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Reference> references = new ArrayList<>();

    /** Replaces the contents of a child collection, attaching each new item to this profile. */
    public <T extends ProfileItem> void replace(List<T> target, Collection<T> items) {
        target.clear();
        for (T item : items) {
            item.setProfile(this);
            target.add(item);
        }
    }
}
