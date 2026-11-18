package za.co.oip.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MiningOperationRepository extends JpaRepository<MiningOperation, UUID> {

    List<MiningOperation> findByMiningCompanyIdOrderByNameAsc(UUID miningCompanyId);

    boolean existsByMiningCompanyIdAndNameIgnoreCase(UUID miningCompanyId, String name);
}
