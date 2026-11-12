package za.co.oip.sources.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Operational scheduling state for a source: when it should next run, its failure streak and the
 * lease that prevents two application instances from crawling it concurrently.
 */
@Getter
@Setter
@Entity
@Table(name = "crawl_schedules")
public class CrawlSchedule {

    /** Upper bound for exponential backoff after repeated failures. */
    public static final Duration MAX_BACKOFF = Duration.ofDays(2);

    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "source_id", nullable = false, unique = true)
    private UUID sourceId;
    @Column(name = "interval_minutes", nullable = false)
    private int intervalMinutes = 1440;
    @Column(name = "next_run_at")
    private Instant nextRunAt;
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;
    @Column(name = "backoff_until")
    private Instant backoffUntil;
    @Column(name = "lease_owner")
    private String leaseOwner;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void recordSuccess(Instant now) {
        consecutiveFailures = 0;
        backoffUntil = null;
        nextRunAt = now.plus(Duration.ofMinutes(intervalMinutes));
        updatedAt = now;
    }

    /**
     * Schedules the next attempt after a failure.
     *
     * <p>Transient failures (source down, timeouts, rate limiting) retry with exponential backoff:
     * 30 min, 1 h, 2 h, 4 h ... capped at {@link #MAX_BACKOFF}. Failures that need human attention (login
     * required, access denied, robots.txt, parser broken) are not retried early: the source waits at least
     * its normal interval, so an unavailable or unwilling source is not hit repeatedly.
     */
    public void recordFailure(Instant now, boolean transientFailure) {
        consecutiveFailures++;
        Duration backoff;
        if (transientFailure) {
            backoff = Duration.ofMinutes(30L << Math.min(consecutiveFailures - 1, 10));
            if (backoff.compareTo(MAX_BACKOFF) > 0) {
                backoff = MAX_BACKOFF;
            }
        } else {
            backoff = Duration.ofMinutes(Math.max(intervalMinutes, 1440));
        }
        backoffUntil = now.plus(backoff);
        nextRunAt = backoffUntil;
        updatedAt = now;
    }
}
