package za.co.oip.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import za.co.oip.common.persistence.BaseEntity;

import java.util.UUID;

/** Membership of a user in a tenant, with the user's role in that tenant. */
@Getter
@Setter
@Entity
@Table(name = "tenant_users")
public class TenantUser extends BaseEntity {

    public enum Status { ACTIVE, DISABLED }

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_code", nullable = false)
    private TenantRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
