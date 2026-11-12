package za.co.oip.companies;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.companies.domain.CompanyProfileRepository;
import za.co.oip.crawling.http.PoliteHttpClient;
import za.co.oip.documents.DocumentStorage;
import za.co.oip.documents.TextExtractor;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Company documents (capability statements, certificates, registrations). Stored per tenant; text is extracted
 * so it can inform matching. Uploading a document does not make any claim "verified".
 */
@Service
public class CompanyDocumentService {

    public static final long MAX_BYTES = 25L * 1024 * 1024;
    static final Set<String> DOCUMENT_TYPES = Set.of("CAPABILITY_STATEMENT", "BBBEE_CERTIFICATE", "CIDB_CERTIFICATE",
            "CSD_REPORT", "TAX_CLEARANCE", "CERTIFICATION", "COMPANY_REGISTRATION", "REFERENCE_LETTER", "OTHER");

    private final CompanyDocumentRepository repository;
    private final CompanyProfileRepository profileRepository;
    private final DocumentStorage storage;
    private final TextExtractor textExtractor;
    private final AuditService auditService;
    private final Clock clock;

    public CompanyDocumentService(CompanyDocumentRepository repository, CompanyProfileRepository profileRepository,
                                  DocumentStorage storage, TextExtractor textExtractor, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.profileRepository = profileRepository;
        this.storage = storage;
        this.textExtractor = textExtractor;
        this.auditService = auditService;
        this.clock = clock;
    }

    public record CompanyDocumentDto(UUID id, String documentType, String fileName, String contentType, long sizeBytes,
                                     String checksumSha256, String description, String extractionStatus, Instant uploadedAt) {
    }

    @Transactional
    public CompanyDocumentDto upload(UUID tenantId, UUID userId, MultipartFile file, String documentType, String description) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("The file is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessRuleException("Files may be at most 25 MB");
        }
        String type = documentType == null ? "OTHER" : documentType.toUpperCase();
        if (!DOCUMENT_TYPES.contains(type)) {
            throw new BusinessRuleException("Unknown document type: " + documentType);
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleException("The file could not be read");
        }
        String fileName = sanitise(file.getOriginalFilename());
        String detected = textExtractor.detect(data, fileName);
        if (!isAllowed(detected)) {
            throw new BusinessRuleException("Unsupported file type (" + detected + "). Upload PDF, Word, Excel, CSV or text files.");
        }
        UUID profileId = profileRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new NotFoundException("Company profile for tenant", tenantId)).getId();
        CompanyDocument doc = new CompanyDocument();
        doc.setTenantId(tenantId);
        doc.setCompanyProfileId(profileId);
        doc.setDocumentType(type);
        doc.setFileName(fileName);
        doc.setContentType(detected);
        doc.setSizeBytes(data.length);
        doc.setChecksumSha256(PoliteHttpClient.sha256(data));
        doc.setStorageKey("company/" + tenantId + "/" + doc.getId());
        doc.setUploadedBy(userId);
        doc.setUploadedAt(clock.instant());
        doc.setDescription(description == null || description.isBlank() ? null : description.trim());
        storage.put(doc.getStorageKey(), data);
        try {
            TextExtractor.Extraction extraction = textExtractor.extract(data, fileName);
            doc.setTextContent(extraction.text());
            doc.setExtractionStatus("EXTRACTED");
        } catch (RuntimeException e) {
            doc.setExtractionStatus("FAILED");
        }
        repository.save(doc);
        auditService.record(tenantId, "COMPANY_DOCUMENT_UPLOADED", "CompanyDocument", doc.getId(),
                Map.of("type", type, "fileName", fileName));
        return toDto(doc);
    }

    @Transactional(readOnly = true)
    public List<CompanyDocumentDto> list(UUID tenantId) {
        return repository.findByTenantIdOrderByUploadedAtDesc(tenantId).stream().map(CompanyDocumentService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Download download(UUID tenantId, UUID id) {
        CompanyDocument doc = load(tenantId, id);
        return new Download(doc.getFileName(), doc.getContentType(), storage.get(doc.getStorageKey()));
    }

    @Transactional
    public void delete(UUID tenantId, UUID id) {
        CompanyDocument doc = load(tenantId, id);
        storage.delete(doc.getStorageKey());
        repository.delete(doc);
        auditService.record(tenantId, "COMPANY_DOCUMENT_DELETED", "CompanyDocument", id, Map.of("fileName", doc.getFileName()));
    }

    /** Extracted text of all the tenant's documents (for matching and the assistant), most recent first. */
    @Transactional(readOnly = true)
    public List<String> extractedTexts(UUID tenantId, int maxChars) {
        return repository.findByTenantIdOrderByUploadedAtDesc(tenantId).stream()
                .map(CompanyDocument::getTextContent)
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.length() > maxChars ? t.substring(0, maxChars) : t)
                .toList();
    }

    public record Download(String fileName, String contentType, byte[] data) {
    }

    private CompanyDocument load(UUID tenantId, UUID id) {
        return repository.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new NotFoundException("Document", id));
    }

    private static boolean isAllowed(String type) {
        return type.equals("application/pdf") || type.startsWith("application/vnd.openxmlformats-officedocument")
                || type.equals("application/msword") || type.equals("application/vnd.ms-excel")
                || type.equals("application/x-tika-ooxml") || type.equals("application/x-tika-msoffice")
                || type.equals("text/plain") || type.equals("text/csv");
    }

    private static String sanitise(String name) {
        String n = name == null ? "document" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return n.isEmpty() ? "document" : n.length() > 200 ? n.substring(0, 200) : n;
    }

    private static CompanyDocumentDto toDto(CompanyDocument d) {
        return new CompanyDocumentDto(d.getId(), d.getDocumentType(), d.getFileName(), d.getContentType(), d.getSizeBytes(),
                d.getChecksumSha256(), d.getDescription(), d.getExtractionStatus(), d.getUploadedAt());
    }

    @Getter
    @Setter
    @Entity(name = "CompanyDocument")
    @Table(name = "company_documents")
    public static class CompanyDocument {
        @Id
        private UUID id = UUID.randomUUID();
        @Column(name = "tenant_id", nullable = false)
        private UUID tenantId;
        @Column(name = "company_profile_id", nullable = false)
        private UUID companyProfileId;
        @Column(name = "document_type", nullable = false)
        private String documentType;
        @Column(name = "file_name", nullable = false)
        private String fileName;
        @Column(name = "content_type", nullable = false)
        private String contentType;
        @Column(name = "size_bytes", nullable = false)
        private long sizeBytes;
        @Column(name = "storage_key", nullable = false)
        private String storageKey;
        @Column(name = "checksum_sha256", nullable = false)
        private String checksumSha256;
        @Column(name = "uploaded_by", nullable = false)
        private UUID uploadedBy;
        @Column(name = "uploaded_at", nullable = false)
        private Instant uploadedAt;
        @Column(name = "text_content")
        private String textContent;
        @Column(name = "extraction_status", nullable = false)
        private String extractionStatus = "PENDING";
        private String description;
    }

    public interface CompanyDocumentRepository extends JpaRepository<CompanyDocument, UUID> {
        List<CompanyDocument> findByTenantIdOrderByUploadedAtDesc(UUID tenantId);

        Optional<CompanyDocument> findByIdAndTenantId(UUID id, UUID tenantId);
    }
}
