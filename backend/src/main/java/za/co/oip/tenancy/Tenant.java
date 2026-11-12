package za.co.oip.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import za.co.oip.common.persistence.BaseEntity;

/** A subscribing business. All tenant-specific data is scoped by the tenant's id. */
@Getter
@Setter
@Entity
@Table(name = "tenants")
public class Tenant extends BaseEntity {

    public enum Status { ACTIVE, SUSPENDED, CLOSED }

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(nullable = false)
    private String timezone = "Africa/Johannesburg";
}
