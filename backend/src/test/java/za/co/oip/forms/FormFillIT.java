package za.co.oip.forms;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import za.co.oip.companies.CompanyFormDetailsIT;
import za.co.oip.documents.DocumentStorage;
import za.co.oip.support.IntegrationTest;
import za.co.oip.support.TestApi.Session;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static za.co.oip.forms.FieldLocatorTest.Text;

/** Filling a tender's own PDF form from the company profile, end to end. All company data is fake. */
class FormFillIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentStorage storage;

    UUID opportunityId;

    static final List<Text> FORM = new ArrayList<>(FieldLocatorTest.SBD1);

    static {
        FORM.add(new Text(40, 300, "Full Name          Identity Number          Shareholding %"));
    }

    @BeforeEach
    void seedOpportunity() {
        opportunityId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into opportunities (id, source_id, source_reference, reference_number, title, description, event_type, status,
                  province, closing_date, source_url, content_hash, first_discovered_at, last_seen_at, last_checked_at, created_at, updated_at)
                values (?, 'c0000000-0000-0000-0000-000000000001', ?, 'TEST/FORM', 'TEST DATA Supply of cables', 'Cables', 'TENDER', 'OPEN',
                  'GAUTENG', ?, 'https://example.test/t.pdf', 'h', ?, ?, ?, ?, ?)""",
                opportunityId, "form-" + opportunityId, LocalDate.now().plusDays(10), now, now, now, now, now);
    }

    Session ownerWithProfile(String name) throws Exception {
        Session s = api().registerTenant(name);
        api().put("/api/v1/companies/profile", s, CompanyFormDetailsIT.details(name)).andExpect(status().isOk());
        api().post("/api/v1/companies/profile/directors", s, Map.of("fullName", "Thandi Example", "role", "DIRECTOR",
                "idType", "SA_ID", "idNumber", CompanyFormDetailsIT.FAKE_ID, "shareholdingPercentage", 100)).andExpect(status().isCreated());
        api().post("/api/v1/tenant/opportunities/" + opportunityId + "/shortlist", s, Map.of()).andExpect(status().isOk());
        return s;
    }

    JsonNode upload(Session s, byte[] pdf) throws Exception {
        return api().read(mvc.perform(multipart("/api/v1/tenant/opportunities/" + opportunityId + "/form-fills/upload")
                        .file(new MockMultipartFile("file", "SBD 1.pdf", "application/pdf", pdf))
                        .header("Authorization", "Bearer " + s.accessToken()))
                .andExpect(status().isCreated()));
    }

    static JsonNode field(JsonNode fill, String field) {
        for (JsonNode p : fill.get("placements")) if (p.get("field").asString().equals(field)) return p;
        throw new AssertionError("no placement for " + field);
    }

    @Test
    void fillReviewGenerateAndDownload() throws Exception {
        Session s = ownerWithProfile("Form Fill Co");
        JsonNode fill = upload(s, FieldLocatorTest.pdf(FORM));
        String base = "/api/v1/tenant/opportunities/" + opportunityId + "/form-fills/" + fill.get("id").asString();

        assertThat(field(fill, "VAT_NUMBER").get("value").asString()).isEqualTo("4123456789");
        assertThat(field(fill, "CSD_NUMBER").get("value").asString()).isEqualTo("0001234");
        assertThat(field(fill, "DIRECTOR_ID").get("value").asString()).startsWith("•").endsWith("9087");
        assertThat(fill.get("pages")).hasSize(1);
        // Drafts never hold identity numbers in clear.
        assertThat(jdbc.queryForObject("select placements::text from form_fills where id = ?::uuid", String.class,
                fill.get("id").asString())).doesNotContain(CompanyFormDetailsIT.FAKE_ID);

        // Preview image.
        mvc.perform(get(base + "/pages/0").header("Authorization", "Bearer " + s.accessToken()))
                .andExpect(status().isOk());

        // The user edits a value and adds free text; typing an identity number into a draft is refused.
        List<JsonNode> edited = new ArrayList<>();
        fill.get("placements").forEach(edited::add);
        ((ObjectNode) field(fill, "CONTACT_PHONE")).put("value", "082 000 0000");
        ObjectNode free = ((ObjectNode) field(fill, "VAT_NUMBER")).deepCopy();
        free.put("id", "manual-1").put("field", "FREE_TEXT").put("source", "MANUAL").put("value", "Signed in Sandton").put("y", 700);
        edited.add(free);
        api().put(base, s, edited).andExpect(status().isOk());
        ObjectNode badId = ((ObjectNode) field(fill, "DIRECTOR_ID")).deepCopy().put("value", CompanyFormDetailsIT.FAKE_ID);
        List<JsonNode> withBadId = new ArrayList<>(edited);
        withBadId.replaceAll(n -> n.get("field").asString().equals("DIRECTOR_ID") ? badId : n);
        api().put(base, s, withBadId).andExpect(status().isUnprocessableContent());

        JsonNode generated = api().read(api().post(base + "/generate", s, null).andExpect(status().isOk()));
        assertThat(generated.get("status").asString()).isEqualTo("GENERATED");
        String docId = generated.get("bidDocumentId").asString();

        JsonNode docs = api().read(api().get("/api/v1/tenant/opportunities/" + opportunityId + "/bid-documents", s));
        assertThat(docs).singleElement().satisfies(d -> assertThat(d.get("fileName").asString()).isEqualTo("SBD 1 (filled).pdf"));

        byte[] pdf = mvc.perform(get("/api/v1/tenant/opportunities/" + opportunityId + "/bid-documents/" + docId)
                .header("Authorization", "Bearer " + s.accessToken())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = FormRenderer.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("4123456789", "0001234", "082 000 0000", "Signed in Sandton", "Thandi Example",
                    CompanyFormDetailsIT.FAKE_ID, "NAME OF BIDDER");
        }
    }

    @Test
    void bidTeamWithoutProfileRightsGetsNoIdentityNumbers() throws Exception {
        Session owner = ownerWithProfile("Withheld Co");
        String email = "bidmgr-" + System.nanoTime() + "@example.test";
        api().post("/api/v1/users", owner, Map.of("email", email, "fullName", "Bid Manager", "role", "BID_MANAGER",
                "initialPassword", "bid-manager-pass-1")).andExpect(status().isCreated());
        Session bidManager = api().login(email, "bid-manager-pass-1");

        JsonNode fill = upload(bidManager, FieldLocatorTest.pdf(FORM));
        assertThat(fill.get("idNumbersWithheld").asBoolean()).isTrue();
        assertThat(field(fill, "DIRECTOR_ID").get("value").asString()).isEmpty();
        String base = "/api/v1/tenant/opportunities/" + opportunityId + "/form-fills/" + fill.get("id").asString();
        String docId = api().read(api().post(base + "/generate", bidManager, null)).get("bidDocumentId").asString();
        byte[] pdf = mvc.perform(get("/api/v1/tenant/opportunities/" + opportunityId + "/bid-documents/" + docId)
                .header("Authorization", "Bearer " + bidManager.accessToken())).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = FormRenderer.load(pdf)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Thandi Example").doesNotContain(CompanyFormDetailsIT.FAKE_ID);
        }
    }

    @Test
    void fillsATenderDocumentAndAPdfInsideAZipPack() throws Exception {
        Session s = ownerWithProfile("Tender Doc Co");
        UUID pdfDoc = tenderDocument("SBD 1.pdf", "application/pdf", FieldLocatorTest.pdf(FORM));
        UUID zipDoc = tenderDocument("Tender pack.zip", "application/zip",
                za.co.oip.documents.TextExtractorZipTest.zip(Map.of("Pack/SBD4.pdf", FieldLocatorTest.pdf(FORM))));

        JsonNode fillable = api().read(api().get("/api/v1/tenant/opportunities/" + opportunityId + "/form-fills", s));
        assertThat(fillable.get("documents").toString()).contains("SBD 1.pdf", "Pack/SBD4.pdf");

        JsonNode a = api().read(api().post("/api/v1/tenant/opportunities/" + opportunityId + "/form-fills", s,
                Map.of("opportunityDocumentId", pdfDoc.toString())).andExpect(status().isCreated()));
        assertThat(field(a, "VAT_NUMBER").get("value").asString()).isEqualTo("4123456789");
        JsonNode b = api().read(api().post("/api/v1/tenant/opportunities/" + opportunityId + "/form-fills", s,
                Map.of("opportunityDocumentId", zipDoc.toString(), "zipEntry", "Pack/SBD4.pdf")).andExpect(status().isCreated()));
        assertThat(b.get("fileName").asString()).isEqualTo("SBD4.pdf");
    }

    @Test
    void fillsAreTenantScopedAndNeedThePipelineItemAndRights() throws Exception {
        Session s = ownerWithProfile("Scoped Fill Co");
        String fillId = upload(s, FieldLocatorTest.pdf(FORM)).get("id").asString();
        String base = "/api/v1/tenant/opportunities/" + opportunityId + "/form-fills/";

        Session other = api().registerTenant("Other Fill Co");
        api().get(base + fillId, other).andExpect(status().isNotFound()); // not in their pipeline
        api().post("/api/v1/tenant/opportunities/" + opportunityId + "/shortlist", other, Map.of()).andExpect(status().isOk());
        api().get(base + fillId, other).andExpect(status().isNotFound()); // their item, not their fill

        String email = "ro-" + System.nanoTime() + "@example.test";
        api().post("/api/v1/users", s, Map.of("email", email, "fullName", "Reader", "role", "READ_ONLY_USER",
                "initialPassword", "reader-password-1")).andExpect(status().isCreated());
        Session reader = api().login(email, "reader-password-1");
        api().get(base + fillId, reader).andExpect(status().isForbidden());
        api().get("/api/v1/tenant/opportunities/" + opportunityId + "/bid-documents", reader).andExpect(status().isOk());

        // Non-PDF uploads are refused.
        mvc.perform(multipart("/api/v1/tenant/opportunities/" + opportunityId + "/form-fills/upload")
                        .file(new MockMultipartFile("file", "form.pdf", "application/pdf", "just text".getBytes()))
                        .header("Authorization", "Bearer " + s.accessToken()))
                .andExpect(status().isUnprocessableContent());
    }

    UUID tenderDocument(String title, String contentType, byte[] data) {
        UUID od = UUID.randomUUID();
        UUID sd = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into opportunity_documents (id, opportunity_id, title, url, first_seen_at, last_seen_at) values (?, ?, ?, ?, ?, ?)",
                od, opportunityId, title, "https://example.test/" + od, now, now);
        String key = "opportunity/" + opportunityId + "/" + sd;
        storage.put(key, data);
        jdbc.update("""
                insert into source_documents (id, opportunity_document_id, opportunity_id, status, content_type, storage_key, created_at, updated_at)
                values (?, ?, ?, 'EXTRACTED', ?, ?, ?, ?)""", sd, od, opportunityId, contentType, key, now, now);
        return od;
    }
}
