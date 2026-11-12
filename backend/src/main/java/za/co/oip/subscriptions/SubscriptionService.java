package za.co.oip.subscriptions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Minimal subscription handling: every new tenant receives the DEFAULT plan. Billing and plan
 * limits are intentionally out of scope until commercial plans are defined.
 */
@Service
public class SubscriptionService {

    /** Seeded in V1__identity_and_tenancy.sql. */
    public static final UUID DEFAULT_PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final TenantSubscriptionRepository repository;
    private final Clock clock;

    public SubscriptionService(TenantSubscriptionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public TenantSubscription startDefaultSubscription(UUID tenantId) {
        Instant now = clock.instant();
        TenantSubscription sub = new TenantSubscription();
        sub.setTenantId(tenantId);
        sub.setPlanId(DEFAULT_PLAN_ID);
        sub.setStatus(TenantSubscription.Status.ACTIVE);
        sub.setStartedAt(now);
        sub.setCreatedAt(now);
        sub.setUpdatedAt(now);
        return repository.save(sub);
    }
}
