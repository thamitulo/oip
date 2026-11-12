package za.co.oip.documents;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import za.co.oip.documents.DocumentEntities.OpportunityRequirement;
import za.co.oip.opportunities.domain.OpportunityEnums.Provenance;

import java.util.List;
import java.util.UUID;

public interface OpportunityRequirementRepository extends JpaRepository<OpportunityRequirement, UUID> {

    List<OpportunityRequirement> findByOpportunityIdOrderByRequirementTypeAsc(UUID opportunityId);

    @Modifying
    @Query("delete from OpportunityRequirement r where r.sourceDocumentId = :documentId and r.provenance = :provenance")
    int deleteByDocumentAndProvenance(UUID documentId, Provenance provenance);

    @Modifying
    @Query("delete from OpportunityRequirement r where r.opportunityId = :opportunityId and r.provenance = :provenance")
    int deleteByOpportunityAndProvenance(UUID opportunityId, Provenance provenance);
}
