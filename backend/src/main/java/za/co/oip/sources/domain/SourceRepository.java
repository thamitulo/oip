package za.co.oip.sources.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.sources.domain.SourceEnums.IntegrationStatus;

import java.util.List;
import java.util.UUID;

public interface SourceRepository extends JpaRepository<Source, UUID> {

    @Query("""
            select s from Source s
            where (:sector is null or s.sector = :sector)
              and (:status is null or s.integrationStatus = :status)
              and (:enabled is null or s.enabled = :enabled)
              and (:q is null or lower(s.name) like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<Source> search(Sector sector, IntegrationStatus status, Boolean enabled, String q, Pageable pageable);

    List<Source> findByOrganisationId(UUID organisationId);

    boolean existsByNameIgnoreCase(String name);
}
