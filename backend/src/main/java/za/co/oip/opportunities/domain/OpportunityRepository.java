package za.co.oip.opportunities.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OpportunityRepository extends JpaRepository<Opportunity, UUID>, JpaSpecificationExecutor<Opportunity> {

    Optional<Opportunity> findBySourceIdAndSourceReference(UUID sourceId, String sourceReference);

    /** Same organisation + same normalised reference number published by a different source. */
    @Query(value = """
            select * from opportunities o
            where o.organisation_id = :organisationId
              and o.source_id <> :sourceId
              and o.duplicate_of_id is null
              and upper(regexp_replace(o.reference_number, '\s+', '', 'g')) = :normalisedReference
            order by o.first_discovered_at asc limit 1
            """, nativeQuery = true)
    Optional<Opportunity> findCrossSourceDuplicate(UUID organisationId, UUID sourceId, String normalisedReference);

    /**
     * Marks opportunities whose closing moment has passed as EXPIRED. Uses the exact closing instant when
     * known, otherwise the end of the closing date in South African time.
     */
    @Modifying
    @Query(value = """
            update opportunities set status = 'EXPIRED', status_inferred = true, updated_at = :now, version = version + 1
            where status in ('OPEN', 'UNKNOWN')
              and ((closing_at is not null and closing_at < :now)
                   or (closing_at is null and closing_date is not null and closing_date < :todaySa))
            """, nativeQuery = true)
    int expireClosed(Instant now, LocalDate todaySa);

    long countBySourceId(UUID sourceId);

    List<Opportunity> findTop20BySourceIdOrderByFirstDiscoveredAtDesc(UUID sourceId);
}
