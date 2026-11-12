package za.co.oip.procurement.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;

import java.util.Optional;
import java.util.UUID;

public interface ProcurementOrganisationRepository extends JpaRepository<ProcurementOrganisation, UUID> {

    @Query("select o from ProcurementOrganisation o where lower(o.name) = lower(:name)")
    Optional<ProcurementOrganisation> findByNameIgnoreCase(String name);

    Optional<ProcurementOrganisation> findByExternalSourceAndExternalId(String externalSource, String externalId);

    @Query("""
            select o from ProcurementOrganisation o
            where (:sector is null or o.sector = :sector)
              and (:q is null or lower(o.name) like lower(concat('%', cast(:q as string), '%'))
                   or lower(o.shortName) like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<ProcurementOrganisation> search(Sector sector, String q, Pageable pageable);
}
