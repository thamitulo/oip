package za.co.oip.opportunities.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SourceChangeEventRepository extends JpaRepository<SourceChangeEvent, UUID> {

    List<SourceChangeEvent> findByOpportunityIdOrderByDetectedAtDesc(UUID opportunityId);

    Page<SourceChangeEvent> findByMaterialTrueAndDetectedAtAfterOrderByDetectedAtDesc(Instant after, Pageable pageable);
}
