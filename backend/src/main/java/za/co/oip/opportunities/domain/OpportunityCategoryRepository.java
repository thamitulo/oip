package za.co.oip.opportunities.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OpportunityCategoryRepository extends JpaRepository<OpportunityCategory, UUID> {

    List<OpportunityCategory> findByOpportunityId(UUID opportunityId);

    boolean existsByOpportunityIdAndCategoryTypeAndCodeAndProvenance(UUID opportunityId,
            OpportunityCategory.CategoryType categoryType, String code, OpportunityCategory.Provenance provenance);
}
