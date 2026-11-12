package za.co.oip.crawling.execution;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import za.co.oip.crawling.execution.CrawlRecords.CrawlError;

import java.util.List;
import java.util.UUID;

public interface CrawlErrorRepository extends JpaRepository<CrawlError, UUID> {

    List<CrawlError> findByRunIdOrderByOccurredAtAsc(UUID runId);

    @Query("select e from CrawlError e where (:sourceId is null or e.sourceId = :sourceId) order by e.occurredAt desc")
    Page<CrawlError> search(UUID sourceId, Pageable pageable);
}
