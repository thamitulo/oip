package za.co.oip.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcurementChannelRepository extends JpaRepository<ProcurementChannel, UUID> {

    List<ProcurementChannel> findByOrganisationIdOrderByChannelTypeAsc(UUID organisationId);
}
