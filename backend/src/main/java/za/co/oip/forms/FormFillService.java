package za.co.oip.forms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.companies.CompanyDirectorService;
import za.co.oip.companies.CompanyDirectorService.DirectorForForm;
import za.co.oip.companies.CompanyProfileService;
import za.co.oip.companies.domain.CompanyProfile;
import za.co.oip.documents.DocumentEntities.SourceDocument;
import za.co.oip.documents.DocumentProcessingService;
import za.co.oip.documents.DocumentStorage;
import za.co.oip.documents.SourceDocumentRepository;
import za.co.oip.documents.TextExtractor;
import za.co.oip.opportunities.domain.OpportunityDocument;
import za.co.oip.opportunities.domain.OpportunityDocumentRepository;
import za.co.oip.pipeline.BidDocumentService;
import za.co.oip.security.AuthenticatedUser;
import za.co.oip.security.Permission;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Pre-fills a tender's own PDF forms from the company profile: picks the form (a tender document, a PDF inside a
 * ZIP pack, or an upload), proposes placements, keeps the user's edits as a draft, and generates the filled PDF as a
 * bid document. Everything is scoped to the tenant's pipeline item for the opportunity.
 */
@Service
public class FormFillService {

    static final long MAX_PDF_BYTES = 40L * 1024 * 1024;
    static final int MAX_PAGES = 150;
    static final int MAX_PLACEMENTS = 600;
    static final float PREVIEW_DPI = 110;
    private static final String PDF = "application/pdf";
    private static final String MASK = "•";

    public record FillableDocument(UUID opportunityDocumentId, String title, String zipEntry, String status) {
    }

    public record FormFillSummary(UUID id, String fileName, String status, Instant updatedAt, UUID bidDocumentId) {
    }

    public record Fillable(List<FillableDocument> documents, List<FormFillSummary> fills, boolean ocrAvailable) {
    }

    public record PageSize(float width, float height) {
    }

    public record FormFillDto(UUID id, UUID opportunityId, String fileName, String status, List<PageSize> pages,
                              List<Placement> placements, List<String> warnings, int ocrPages, UUID bidDocumentId,
                              boolean idNumbersWithheld) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final OpportunityDocumentRepository opportunityDocuments;
    private final SourceDocumentRepository sourceDocuments;
    private final DocumentProcessingService processing;
    private final DocumentStorage storage;
    private final TextExtractor textExtractor;
    private final CompanyProfileService profiles;
    private final CompanyDirectorService directors;
    private final BidDocumentService bidDocuments;
    private final PdfLayoutReader layoutReader;
    private final TesseractOcr ocr;
    private final JsonMapper json;
    private final AuditService audit;
    private final Clock clock;

    public FormFillService(NamedParameterJdbcTemplate jdbc, OpportunityDocumentRepository opportunityDocuments,
                           SourceDocumentRepository sourceDocuments, DocumentProcessingService processing,
                           DocumentStorage storage, TextExtractor textExtractor, CompanyProfileService profiles,
                           CompanyDirectorService directors, BidDocumentService bidDocuments, PdfLayoutReader layoutReader,
                           TesseractOcr ocr, JsonMapper json, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.opportunityDocuments = opportunityDocuments;
        this.sourceDocuments = sourceDocuments;
        this.processing = processing;
        this.storage = storage;
        this.textExtractor = textExtractor;
        this.profiles = profiles;
        this.directors = directors;
        this.bidDocuments = bidDocuments;
        this.layoutReader = layoutReader;
        this.ocr = ocr;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- choosing a form

    public Fillable fillable(AuthenticatedUser user, UUID opportunityId) {
        UUID item = bidDocuments.requireItem(user.tenantId(), opportunityId);
        Map<UUID, SourceDocument> byDoc = sourceDocuments.findByOpportunityId(opportunityId).stream()
                .filter(s -> s.getOpportunityDocumentId() != null)
                .collect(Collectors.toMap(SourceDocument::getOpportunityDocumentId, Function.identity(), (a, b) -> a));
        List<FillableDocument> docs = new ArrayList<>();
        for (OpportunityDocument d : opportunityDocuments.findByOpportunityIdOrderByFirstSeenAtAsc(opportunityId)) {
            if (d.getRemovedAt() != null) continue;
            String title = d.getTitle() == null || d.getTitle().isBlank() ? fileNameOf(d.getUrl()) : d.getTitle();
            SourceDocument sd = byDoc.get(d.getId());
            if (sd == null || sd.getStorageKey() == null || !storage.exists(sd.getStorageKey())) {
                if (!looksLikeNonPdf(d)) docs.add(new FillableDocument(d.getId(), title, null, "NOT_DOWNLOADED"));
            } else if (PDF.equals(sd.getContentType())) {
                docs.add(new FillableDocument(d.getId(), title, null, "READY"));
            } else if (TextExtractorZip.ZIP.equals(sd.getContentType())) {
                for (String entry : TextExtractorZip.pdfEntries(storage.get(sd.getStorageKey()))) {
                    docs.add(new FillableDocument(d.getId(), title + " › " + entry.substring(entry.lastIndexOf('/') + 1), entry, "READY"));
                }
            }
        }
        List<FormFillSummary> fills = jdbc.query("""
                        select id, file_name, status, updated_at, bid_document_id from form_fills
                        where tenant_id = :t and tenant_opportunity_id = :i order by updated_at desc""",
                new MapSqlParameterSource("t", user.tenantId()).addValue("i", item),
                (rs, n) -> new FormFillSummary(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getObject(5, UUID.class)));
        return new Fillable(docs, fills, ocr.isAvailable());
    }

    /** Starts a fill from a tender document (or a PDF inside its ZIP pack). Downloads it first if needed. */
    public FormFillDto startFromDocument(AuthenticatedUser user, UUID opportunityId, UUID opportunityDocumentId, String zipEntry) {
        UUID item = bidDocuments.requireItem(user.tenantId(), opportunityId);
        OpportunityDocument d = opportunityDocuments.findById(opportunityDocumentId)
                .filter(x -> x.getOpportunityId().equals(opportunityId))
                .orElseThrow(() -> new NotFoundException("Document", opportunityDocumentId));
        SourceDocument sd = sourceDocuments.findByOpportunityDocumentId(d.getId()).orElse(null);
        if (sd == null || sd.getStorageKey() == null || !storage.exists(sd.getStorageKey())) {
            processing.process(d.getId());
            sd = sourceDocuments.findByOpportunityDocumentId(d.getId()).orElse(null);
            if (sd == null || sd.getStorageKey() == null || !storage.exists(sd.getStorageKey())) {
                throw new BusinessRuleException("The document could not be downloaded from the source"
                        + (sd != null && sd.getError() != null ? ": " + sd.getError() : "") + ". Download it yourself and upload it.");
            }
        }
        byte[] data = storage.get(sd.getStorageKey());
        String name = d.getTitle() == null || d.getTitle().isBlank() ? fileNameOf(d.getUrl()) : d.getTitle();
        if (zipEntry != null && !zipEntry.isBlank()) {
            data = TextExtractorZip.entry(data, zipEntry);
            name = zipEntry.substring(zipEntry.lastIndexOf('/') + 1);
        }
        return start(user, item, opportunityId, sd.getId(), name, data);
    }

    /** Starts a fill from a PDF the user uploads (e.g. a form only available on the buyer's portal). */
    public FormFillDto startFromUpload(AuthenticatedUser user, UUID opportunityId, MultipartFile file) {
        UUID item = bidDocuments.requireItem(user.tenantId(), opportunityId);
        if (file == null || file.isEmpty()) throw new BusinessRuleException("The file is empty");
        if (file.getSize() > BidDocumentService.MAX_BYTES) throw new BusinessRuleException("Forms may be at most 25 MB");
        try {
            return start(user, item, opportunityId, null, BidDocumentService.sanitise(file.getOriginalFilename()), file.getBytes());
        } catch (IOException e) {
            throw new BusinessRuleException("The file could not be read");
        }
    }

    private FormFillDto start(AuthenticatedUser user, UUID item, UUID opportunityId, UUID sourceDocumentId, String name, byte[] data) {
        if (!PDF.equals(textExtractor.detect(data, name))) {
            throw new BusinessRuleException("Only PDF forms can be filled at the moment. Word forms are planned.");
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) name = name + ".pdf";
        boolean withheld = !may(user, Permission.COMPANY_PROFILE_EDIT);
        FillData fillData = fillData(user.tenantId(), withheld);
        List<Placement> placements;
        List<String> warnings;
        int ocrPages;
        try (PDDocument doc = FormRenderer.load(data)) {
            if (doc.getNumberOfPages() > MAX_PAGES) {
                throw new BusinessRuleException("This document has " + doc.getNumberOfPages() + " pages; forms of up to "
                        + MAX_PAGES + " pages can be filled. Upload just the form pages.");
            }
            PdfLayoutReader.Result layout = layoutReader.read(doc);
            FieldLocator.Result located = FieldLocator.locate(layout.pages(), fillData);
            List<Placement> acro = FieldLocator.acroForm(doc, fillData);
            // A real form field beats a guess from a printed label for the same detail on the same page.
            Set<String> acroKeys = new HashSet<>();
            acro.forEach(p -> acroKeys.add(p.page() + ":" + p.field()));
            placements = new ArrayList<>(acro);
            located.placements().stream().filter(p -> !acroKeys.contains(p.page() + ":" + p.field())).forEach(placements::add);
            warnings = new ArrayList<>(layout.warnings());
            warnings.addAll(located.warnings());
            if (placements.isEmpty()) {
                warnings.add("No form fields were recognised. Click on a page to place values yourself.");
            }
            ocrPages = layout.ocrPages();
        } catch (IOException e) {
            throw new BusinessRuleException("The PDF could not be read: " + e.getMessage());
        }
        UUID id = UUID.randomUUID();
        String key = "formfill/" + user.tenantId() + "/" + id; // DocumentStorage keys are prefix/uuid/...
        storage.put(key, data);
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into form_fills (id, tenant_id, tenant_opportunity_id, source_document_id, storage_key, file_name, page_count,
                                        status, placements, ocr_pages, warnings, created_by, created_at, updated_at)
                values (:id, :t, :i, :sd, :k, :n, :pc, 'DRAFT', cast(:p as jsonb), :ocr, cast(:w as jsonb), :u, :now, :now)""",
                new MapSqlParameterSource("id", id).addValue("t", user.tenantId()).addValue("i", item)
                        .addValue("sd", sourceDocumentId).addValue("k", key).addValue("n", name)
                        .addValue("pc", pageCount(data)).addValue("p", json.writeValueAsString(placements))
                        .addValue("ocr", ocrPages).addValue("w", json.writeValueAsString(warnings))
                        .addValue("u", user.userId()).addValue("now", now));
        audit.record(user.tenantId(), "FORM_FILL_STARTED", "FormFill", id,
                Map.of("fileName", name, "placements", placements.size(), "ocrPages", ocrPages));
        return get(user, opportunityId, id);
    }

    // ---------------------------------------------------------------- reviewing

    public FormFillDto get(AuthenticatedUser user, UUID opportunityId, UUID fillId) {
        Row r = row(user, opportunityId, fillId);
        List<PageSize> pages = new ArrayList<>();
        try (PDDocument doc = FormRenderer.load(storage.get(r.storageKey()))) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDRectangle box = doc.getPage(i).getCropBox();
                boolean rotated = doc.getPage(i).getRotation() % 180 != 0;
                pages.add(rotated ? new PageSize(box.getHeight(), box.getWidth()) : new PageSize(box.getWidth(), box.getHeight()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Stored form could not be read", e);
        }
        return new FormFillDto(fillId, opportunityId, r.fileName(), r.status(), pages, r.placements(), r.warnings(), r.ocrPages(),
                r.bidDocumentId(), !may(user, Permission.COMPANY_PROFILE_EDIT));
    }

    public byte[] preview(AuthenticatedUser user, UUID opportunityId, UUID fillId, int page) {
        Row r = row(user, opportunityId, fillId);
        if (page < 0 || page >= r.pageCount()) throw new NotFoundException("Page", page);
        try {
            return FormRenderer.preview(storage.get(r.storageKey()), page, PREVIEW_DPI);
        } catch (IOException e) {
            throw new IllegalStateException("Preview failed", e);
        }
    }

    public FormFillDto update(AuthenticatedUser user, UUID opportunityId, UUID fillId, List<Placement> placements) {
        Row r = row(user, opportunityId, fillId);
        if (placements.size() > MAX_PLACEMENTS) throw new BusinessRuleException("At most " + MAX_PLACEMENTS + " values per form");
        Set<String> ids = new HashSet<>();
        List<Placement> clean = new ArrayList<>();
        for (Placement p : placements) {
            if (!ids.add(p.id())) throw new BusinessRuleException("Duplicate placement id " + p.id());
            if (p.page() >= r.pageCount()) throw new BusinessRuleException("Page " + (p.page() + 1) + " does not exist");
            String value = p.value() == null ? "" : p.value();
            if ("DIRECTOR_ID".equals(p.field()) && !value.isEmpty() && !value.startsWith(MASK)) {
                // Identity numbers come only from the encrypted directors list, never typed into a draft.
                throw new BusinessRuleException("Identity numbers are filled in from the directors in your company profile");
            }
            clean.add(p.withValue(value, value.isBlank() && p.missing()));
        }
        jdbc.update("update form_fills set placements = cast(:p as jsonb), status = 'DRAFT', updated_at = :now where id = :id",
                new MapSqlParameterSource("p", json.writeValueAsString(clean)).addValue("id", fillId)
                        .addValue("now", Timestamp.from(clock.instant())));
        return get(user, opportunityId, fillId);
    }

    // ---------------------------------------------------------------- generating

    public FormFillDto generate(AuthenticatedUser user, UUID opportunityId, UUID fillId) {
        Row r = row(user, opportunityId, fillId);
        boolean mayIds = may(user, Permission.COMPANY_PROFILE_EDIT);
        Map<String, String> idNumbers = mayIds
                ? directors.forForms(user.tenantId(), true).stream().filter(d -> d.idNumber() != null)
                        .collect(Collectors.toMap(d -> d.id().toString(), DirectorForForm::idNumber))
                : Map.of();
        List<Placement> resolved = r.placements().stream().map(p -> {
            if (!"DIRECTOR_ID".equals(p.field()) || p.value() == null || !p.value().startsWith(MASK)) return p;
            String real = p.ref() == null ? null : idNumbers.get(p.ref());
            return p.withValue(real == null ? "" : real, real == null);
        }).toList();
        byte[] filled;
        try {
            filled = FormRenderer.fill(storage.get(r.storageKey()), resolved);
        } catch (IOException e) {
            throw new BusinessRuleException("The filled PDF could not be created: " + e.getMessage());
        }
        String name = r.fileName().replaceFirst("(?i)\\.pdf$", "") + " (filled).pdf";
        var doc = bidDocuments.store(user.tenantId(), user.userId(), r.itemId(), name, PDF, filled);
        jdbc.update("update form_fills set status = 'GENERATED', bid_document_id = :b, updated_at = :now where id = :id",
                new MapSqlParameterSource("b", doc.id()).addValue("id", fillId).addValue("now", Timestamp.from(clock.instant())));
        audit.record(user.tenantId(), "FORM_FILL_GENERATED", "FormFill", fillId, Map.of("bidDocumentId", doc.id(),
                "fields", resolved.stream().filter(p -> p.value() != null && !p.value().isBlank()).map(Placement::field).distinct().toList()));
        return get(user, opportunityId, fillId);
    }

    public void delete(AuthenticatedUser user, UUID opportunityId, UUID fillId) {
        Row r = row(user, opportunityId, fillId);
        jdbc.update("delete from form_fills where id = :id", Map.of("id", fillId));
        storage.delete(r.storageKey());
        audit.record(user.tenantId(), "FORM_FILL_DELETED", "FormFill", fillId, Map.of("fileName", r.fileName()));
    }

    // ---------------------------------------------------------------- data

    /** Company details formatted the way forms expect them. */
    FillData fillData(UUID tenantId, boolean idNumbersWithheld) {
        CompanyProfile p = profiles.load(tenantId);
        Map<FormField, String> v = new EnumMap<>(FormField.class);
        v.put(FormField.COMPANY_NAME, p.getLegalName());
        v.put(FormField.TRADING_NAME, p.getTradingName());
        v.put(FormField.REGISTRATION_NUMBER, p.getRegistrationNumber());
        v.put(FormField.VAT_NUMBER, p.getVatNumber());
        v.put(FormField.CSD_NUMBER, p.getCsdSupplierNumber());
        v.put(FormField.INCOME_TAX_NUMBER, p.getIncomeTaxNumber());
        v.put(FormField.UIF_NUMBER, p.getUifReferenceNumber());
        v.put(FormField.COMPENSATION_FUND_NUMBER, p.getCompensationFundNumber());
        v.put(FormField.CIDB_CRS_NUMBER, p.getCidbCrsNumber());
        v.put(FormField.CIDB_GRADING, p.getCidbGrading());
        v.put(FormField.PHYSICAL_ADDRESS, p.getPhysicalAddress());
        v.put(FormField.POSTAL_ADDRESS, p.getPostalAddress());
        v.put(FormField.CONTACT_NAME, p.getContactName());
        v.put(FormField.CONTACT_PHONE, p.getContactPhone());
        v.put(FormField.CONTACT_EMAIL, p.getContactEmail());
        if (p.getBbbeeLevel() != null) {
            String level = p.getBbbeeLevel().name();
            v.put(FormField.BBBEE_LEVEL, level.startsWith("LEVEL_") ? "Level " + level.substring(6)
                    : level.charAt(0) + level.substring(1).toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        v.put(FormField.BBBEE_CERTIFICATE_NUMBER, p.getBbbeeCertificateNumber());
        if (p.getBbbeeCertificateExpiry() != null) {
            v.put(FormField.BBBEE_EXPIRY, p.getBbbeeCertificateExpiry().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")));
        }
        v.put(FormField.BBBEE_AGENCY, p.getBbbeeVerificationAgency());
        v.values().removeIf(x -> x == null || x.isBlank());
        return new FillData(v, directors.forForms(tenantId, false), idNumbersWithheld);
    }

    private record Row(UUID itemId, String storageKey, String fileName, int pageCount, String status, List<Placement> placements,
                       List<String> warnings, int ocrPages, UUID bidDocumentId) {
    }

    private Row row(AuthenticatedUser user, UUID opportunityId, UUID fillId) {
        UUID item = bidDocuments.requireItem(user.tenantId(), opportunityId);
        List<Row> rows = jdbc.query("""
                        select storage_key, file_name, page_count, status, placements::text, warnings::text, ocr_pages, bid_document_id
                        from form_fills where id = :id and tenant_id = :t and tenant_opportunity_id = :i""",
                new MapSqlParameterSource("id", fillId).addValue("t", user.tenantId()).addValue("i", item),
                (rs, n) -> new Row(item, rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        json.readValue(rs.getString(5), new TypeReference<List<Placement>>() { }),
                        json.readValue(rs.getString(6), new TypeReference<List<String>>() { }),
                        rs.getInt(7), rs.getObject(8, UUID.class)));
        if (rows.isEmpty()) throw new NotFoundException("Form fill", fillId);
        return rows.getFirst();
    }

    private static boolean may(AuthenticatedUser user, Permission permission) {
        return user.role() != null && user.role().permissions().contains(permission);
    }

    private static int pageCount(byte[] pdf) {
        try (PDDocument doc = FormRenderer.load(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new BusinessRuleException("The PDF could not be read");
        }
    }

    private static boolean looksLikeNonPdf(OpportunityDocument d) {
        String f = (d.getFileFormat() == null ? fileNameOf(d.getUrl()) : d.getFileFormat()).toLowerCase(Locale.ROOT);
        return f.endsWith("doc") || f.endsWith("docx") || f.endsWith("xls") || f.endsWith("xlsx") || f.equals("docx")
                || f.equals("doc") || f.equals("xls") || f.equals("xlsx");
    }

    static String fileNameOf(String url) {
        if (url == null) return "document.pdf";
        String path = url.replaceFirst("[?#].*$", "");
        String n = path.substring(path.lastIndexOf('/') + 1);
        return n.isBlank() ? "document.pdf" : java.net.URLDecoder.decode(n, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** PDFs inside ZIP tender packs: listing and reading one entry, in memory and size-limited. */
    static final class TextExtractorZip {
        static final String ZIP = "application/zip";

        private TextExtractorZip() {
        }

        static List<String> pdfEntries(byte[] zip) {
            List<String> out = new ArrayList<>();
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
                for (ZipEntry e; (e = in.getNextEntry()) != null && out.size() < 60; ) {
                    String n = e.getName();
                    String base = n.substring(n.lastIndexOf('/') + 1);
                    if (!e.isDirectory() && !n.startsWith("__MACOSX/") && !base.startsWith(".")
                            && base.toLowerCase(Locale.ROOT).endsWith(".pdf")) out.add(n);
                }
            } catch (IOException e) {
                return List.of();
            }
            return out;
        }

        static byte[] entry(byte[] zip, String name) {
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
                for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                    if (e.getName().equals(name)) {
                        byte[] data = in.readNBytes((int) MAX_PDF_BYTES + 1);
                        if (data.length > MAX_PDF_BYTES) throw new BusinessRuleException("That file in the pack is too large");
                        return data;
                    }
                }
            } catch (IOException e) {
                throw new BusinessRuleException("The ZIP pack could not be read");
            }
            throw new NotFoundException("File in ZIP pack", name);
        }
    }
}
