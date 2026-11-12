package za.co.oip.documents;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import za.co.oip.documents.DocumentEntities.SourceDocument;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, UUID> {

    Optional<SourceDocument> findByOpportunityDocumentId(UUID opportunityDocumentId);

    List<SourceDocument> findByOpportunityId(UUID opportunityId);

    /**
     * Documents of open opportunities from enabled sources that have not been processed yet (or are due for a
     * retry), soonest-closing first.
     */
    @Query(value = """
            select d.id from opportunity_documents d
            join opportunities o on o.id = d.opportunity_id
            join source_registry s on s.id = o.source_id
            left join source_documents sd on sd.opportunity_document_id = d.id
            where d.removed_at is null and o.status in ('OPEN', 'UNKNOWN')
              and (o.closing_date is null or o.closing_date >= :today)
              and s.enabled and s.terms_review_status <> 'PROHIBITED'
              and (sd.id is null or (sd.status = 'PENDING' or (sd.status = 'FAILED' and sd.attempts < 3))
                   and (sd.next_attempt_at is null or sd.next_attempt_at <= :now))
            order by o.closing_date asc nulls last, d.first_seen_at asc
            limit :limit
            """, nativeQuery = true)
    List<UUID> findDocumentsToProcess(LocalDate today, Instant now, int limit);

    /** Originals of opportunities that closed more than the retention period ago. */
    @Query(value = """
            select sd.* from source_documents sd join opportunities o on o.id = sd.opportunity_id
            where sd.storage_key is not null and sd.original_deleted_at is null
              and o.closing_date is not null and o.closing_date < :before
            limit 500
            """, nativeQuery = true)
    List<SourceDocument> findOriginalsToExpire(LocalDate before);
}
