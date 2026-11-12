package za.co.oip.companies.domain;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** A row owned by a company profile. The tenant id is copied from the profile on insert. */
@Getter
@Setter
@MappedSuperclass
public abstract class ProfileItem {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_profile_id", nullable = false, updatable = false)
    private CompanyProfile profile;

    @PrePersist
    void copyTenant() {
        this.tenantId = profile.getTenantId();
    }
}
