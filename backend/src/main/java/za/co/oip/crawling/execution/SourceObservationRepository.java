package za.co.oip.crawling.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import za.co.oip.crawling.execution.CrawlRecords.SourceObservation;

import java.util.List;
import java.util.UUID;

public interface SourceObservationRepository extends JpaRepository<SourceObservation, UUID> {

    List<SourceObservation> findByRunIdOrderByFetchedAtAsc(UUID runId);
}
