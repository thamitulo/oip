package za.co.oip.tenancy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantUserRepository extends JpaRepository<TenantUser, UUID> {

    List<TenantUser> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);

    List<TenantUser> findByUserIdAndStatusOrderByCreatedAtAsc(UUID userId, TenantUser.Status status);

    Optional<TenantUser> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    long countByTenantIdAndRoleAndStatus(UUID tenantId, TenantRole role, TenantUser.Status status);
}
