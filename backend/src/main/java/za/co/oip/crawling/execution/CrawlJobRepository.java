package za.co.oip.crawling.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.oip.crawling.execution.CrawlRecords.CrawlJob;

import java.util.UUID;

public interface CrawlJobRepository extends JpaRepository<CrawlJob, UUID> {
}
