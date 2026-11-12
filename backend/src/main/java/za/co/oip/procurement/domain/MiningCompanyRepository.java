package za.co.oip.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MiningCompanyRepository extends JpaRepository<MiningCompany, UUID> {

    Optional<MiningCompany> findByOrganisationId(UUID organisationId);
}
