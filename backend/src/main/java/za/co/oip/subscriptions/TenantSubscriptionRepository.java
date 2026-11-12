package za.co.oip.subscriptions;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, UUID> {
    List<TenantSubscription> findByTenantId(UUID tenantId);
}
