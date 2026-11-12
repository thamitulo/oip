package za.co.oip.crawling.execution;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import za.co.oip.crawling.execution.CrawlRecords.CrawlJobRun;
import za.co.oip.crawling.execution.CrawlRecords.RunStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CrawlJobRunRepository extends JpaRepository<CrawlJobRun, UUID> {

    @Query("""
            select r from CrawlJobRun r
            where (:sourceId is null or r.sourceId = :sourceId)
              and (:status is null or r.status = :status)
            order by r.startedAt desc
            """)
    Page<CrawlJobRun> search(UUID sourceId, RunStatus status, Pageable pageable);

    Optional<CrawlJobRun> findFirstBySourceIdOrderByStartedAtDesc(UUID sourceId);

    Optional<CrawlJobRun> findFirstBySourceIdAndStatusOrderByStartedAtDesc(UUID sourceId, RunStatus status);

    List<CrawlJobRun> findByJobIdOrderByStartedAtAsc(UUID jobId);
}
