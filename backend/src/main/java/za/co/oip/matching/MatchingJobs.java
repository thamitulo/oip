package za.co.oip.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import za.co.oip.companies.CompanyProfileService.ProfileChangedEvent;

import java.util.UUID;

/** Keeps every tenant's matches current as opportunities and profiles change. */
@Component
@ConditionalOnProperty(prefix = "oip.matching", name = "background-enabled", havingValue = "true", matchIfMissing = true)
public class MatchingJobs {

    private static final Logger log = LoggerFactory.getLogger(MatchingJobs.class);
    private final MatchingService service;
    private final TaskExecutor executor;

    public MatchingJobs(MatchingService service, @Qualifier("crawlExecutor") TaskExecutor executor) {
        this.service = service;
        this.executor = executor;
    }

    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "${oip.matching.refresh-interval:PT10M}")
    public void refreshAll() {
        for (UUID tenant : service.tenantIds()) {
            refresh(tenant);
        }
    }

    /** A profile change makes the tenant's matches stale; recompute them in the background. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProfileChanged(ProfileChangedEvent event) {
        executor.execute(() -> refresh(event.tenantId()));
    }

    private void refresh(UUID tenant) {
        try {
            int n = service.refresh(tenant, 5000);
            if (n > 0) log.info("Recomputed {} match(es) for tenant {}", n, tenant);
        } catch (RuntimeException e) {
            log.warn("Match refresh failed for tenant {}: {}", tenant, e.getMessage());
        }
    }
}
