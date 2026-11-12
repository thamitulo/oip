package za.co.oip.opportunities.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OpportunityDocumentRepository extends JpaRepository<OpportunityDocument, UUID> {

    List<OpportunityDocument> findByOpportunityIdOrderByFirstSeenAtAsc(UUID opportunityId);
}
