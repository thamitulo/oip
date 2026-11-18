package za.co.oip.pipeline;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.crawling.http.PoliteHttpClient;
import za.co.oip.documents.DocumentStorage;
import za.co.oip.documents.TextExtractor;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Documents a tenant prepares for one bid (filled forms, uploads), attached to its pipeline item. Tenant-scoped:
 * every lookup goes through the tenant's own pipeline item.
 */
@Service
public class BidDocumentService {

    public static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final Set<String> ALLOWED = Set.of("application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/x-tika-ooxml",
            "application/x-tika-msoffice", "image/png", "image/jpeg", "text/plain", "text/csv");

    public record BidDocumentDto(UUID id, String fileName, String contentType, long sizeBytes, String uploadedByName,
                                 Instant uploadedAt) {
    }

    public record Download(String fileName, String contentType, byte[] data) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final DocumentStorage storage;
    private final TextExtractor textExtractor;
    private final AuditService audit;
    private final Clock clock;

    public BidDocumentService(NamedParameterJdbcTemplate jdbc, DocumentStorage storage, TextExtractor textExtractor,
                              AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.textExtractor = textExtractor;
        this.audit = audit;
        this.clock = clock;
    }

    /** The tenant's pipeline item for an opportunity; 404 when the opportunity is not in this tenant's pipeline. */
    public UUID requireItem(UUID tenantId, UUID opportunityId) {
        List<UUID> ids = jdbc.queryForList("select id from tenant_opportunities where tenant_id = :t and opportunity_id = :o",
                new MapSqlParameterSource("t", tenantId).addValue("o", opportunityId), UUID.class);
        if (ids.isEmpty()) throw new NotFoundException("Pipeline item for opportunity", opportunityId);
        return ids.getFirst();
    }

    @Transactional(readOnly = true)
    public List<BidDocumentDto> list(UUID tenantId, UUID opportunityId) {
        UUID item = requireItem(tenantId, opportunityId);
        return jdbc.query("""
                        select d.id, d.file_name, d.content_type, d.size_bytes, u.full_name, d.uploaded_at
                        from bid_documents d left join users u on u.id = d.uploaded_by
                        where d.tenant_id = :t and d.tenant_opportunity_id = :i order by d.uploaded_at desc""",
                new MapSqlParameterSource("t", tenantId).addValue("i", item),
                (rs, n) -> new BidDocumentDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant()));
    }

    @Transactional
    public BidDocumentDto upload(UUID tenantId, UUID userId, UUID opportunityId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessRuleException("The file is empty");
        if (file.getSize() > MAX_BYTES) throw new BusinessRuleException("Files may be at most 25 MB");
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("The file could not be read");
        }
        String name = sanitise(file.getOriginalFilename());
        String type = textExtractor.detect(data, name);
        if (!ALLOWED.contains(type)) {
            throw new BusinessRuleException("Unsupported file type (" + type + "). Upload PDF, Word, Excel, images or text.");
        }
        return store(tenantId, userId, requireItem(tenantId, opportunityId), name, type, data);
    }

    /** Stores a document produced by the platform (e.g. a filled form) on a pipeline item. */
    @Transactional
    public BidDocumentDto store(UUID tenantId, UUID userId, UUID itemId, String fileName, String contentType, byte[] data) {
        UUID id = UUID.randomUUID();
        String key = "bid/" + tenantId + "/" + itemId + "/" + id;
        storage.put(key, data);
        Instant now = clock.instant();
        jdbc.update("""
                insert into bid_documents (id, tenant_id, tenant_opportunity_id, file_name, content_type, size_bytes,
                                           storage_key, checksum_sha256, uploaded_by, uploaded_at)
                values (:id, :t, :i, :n, :ct, :s, :k, :h, :u, :now)""",
                new MapSqlParameterSource("id", id).addValue("t", tenantId).addValue("i", itemId).addValue("n", fileName)
                        .addValue("ct", contentType).addValue("s", data.length).addValue("k", key)
                        .addValue("h", PoliteHttpClient.sha256(data)).addValue("u", userId).addValue("now", Timestamp.from(now)));
        audit.record(tenantId, "BID_DOCUMENT_ADDED", "BidDocument", id, Map.of("fileName", fileName));
        return new BidDocumentDto(id, fileName, contentType, data.length, null, now);
    }

    @Transactional(readOnly = true)
    public Download download(UUID tenantId, UUID opportunityId, UUID documentId) {
        Map<String, Object> row = row(tenantId, opportunityId, documentId);
        return new Download((String) row.get("file_name"), (String) row.get("content_type"),
                storage.get((String) row.get("storage_key")));
    }

    @Transactional
    public void delete(UUID tenantId, UUID opportunityId, UUID documentId) {
        Map<String, Object> row = row(tenantId, opportunityId, documentId);
        jdbc.update("delete from bid_documents where id = :id", Map.of("id", documentId));
        storage.delete((String) row.get("storage_key"));
        audit.record(tenantId, "BID_DOCUMENT_DELETED", "BidDocument", documentId, Map.of("fileName", row.get("file_name")));
    }

    private Map<String, Object> row(UUID tenantId, UUID opportunityId, UUID documentId) {
        UUID item = requireItem(tenantId, opportunityId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select file_name, content_type, storage_key from bid_documents where id = :id and tenant_id = :t and tenant_opportunity_id = :i",
                new MapSqlParameterSource("id", documentId).addValue("t", tenantId).addValue("i", item));
        if (rows.isEmpty()) throw new NotFoundException("Bid document", documentId);
        return rows.getFirst();
    }

    public static String sanitise(String name) {
        String n = name == null ? "document" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty()) n = "document";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }
}
