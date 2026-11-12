package za.co.oip.sources.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CrawlScheduleRepository extends JpaRepository<CrawlSchedule, UUID> {

    Optional<CrawlSchedule> findBySourceId(UUID sourceId);

    /** Schedules of enabled sources that are due and not currently leased. */
    @Query("""
            select c from CrawlSchedule c, Source s
            where s.id = c.sourceId and s.enabled = true
              and (c.nextRunAt is null or c.nextRunAt <= :now)
              and (c.leaseUntil is null or c.leaseUntil < :now)
            order by s.priority asc, c.nextRunAt asc
            """)
    List<CrawlSchedule> findDue(Instant now);

    /**
     * Atomically acquires the crawl lease for a source. Returns 1 if this caller obtained the lease, 0 if
     * another worker holds an unexpired lease. This is what makes scheduling safe across instances.
     */
    @Modifying
    @Query("""
            update CrawlSchedule c set c.leaseOwner = :owner, c.leaseUntil = :until
            where c.sourceId = :sourceId and (c.leaseUntil is null or c.leaseUntil < :now)
            """)
    int acquireLease(UUID sourceId, String owner, Instant now, Instant until);

    @Modifying
    @Query("update CrawlSchedule c set c.leaseOwner = null, c.leaseUntil = null where c.sourceId = :sourceId and c.leaseOwner = :owner")
    int releaseLease(UUID sourceId, String owner);
}
