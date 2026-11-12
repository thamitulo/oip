package za.co.oip.procurement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import za.co.oip.common.persistence.BaseEntity;
import za.co.oip.procurement.domain.ProcurementEnums.OrganisationType;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.procurement.domain.ProcurementEnums.WebsiteVerification;

import java.time.Instant;

/** An organisation that publishes procurement opportunities (government entity, mining company, etc.). */
@Getter
@Setter
@Entity
@Table(name = "procurement_organisations")
public class ProcurementOrganisation extends BaseEntity {

    @Column(nullable = false)
    private String name;
    @Column(name = "short_name")
    private String shortName;
    @Enumerated(EnumType.STRING)
    @Column(name = "organisation_type", nullable = false)
    private OrganisationType organisationType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sector sector;
    private String website;
    @Enumerated(EnumType.STRING)
    @Column(name = "website_verification", nullable = false)
    private WebsiteVerification websiteVerification = WebsiteVerification.UNVERIFIED;
    @Column(name = "website_verified_at")
    private Instant websiteVerifiedAt;
    private String province;
    @Column(nullable = false)
    private String country = "ZA";
    /** Identifier namespace of the external system this organisation was imported from (e.g. ETENDERS). */
    @Column(name = "external_source")
    private String externalSource;
    @Column(name = "external_id")
    private String externalId;
    private String notes;
}
