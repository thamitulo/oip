package za.co.oip.documents;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.execution.CrawlRecords.TriggerType;
import za.co.oip.crawling.execution.CrawlService;
import za.co.oip.opportunities.domain.Opportunity;
import za.co.oip.opportunities.domain.OpportunityEnums.Provenance;
import za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod;
import za.co.oip.opportunities.domain.OpportunityRepository;
import za.co.oip.support.FixtureServer;
import za.co.oip.support.IntegrationTest;
import za.co.oip.support.TestApi.Session;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Document pipeline end to end over a fixture site whose tender details are only in a PDF (synthetic data). */
class DocumentPipelineIT extends IntegrationTest {

    @Autowired
    CrawlService crawlService;
    @Autowired
    DocumentProcessingService processing;
    @Autowired
    OpportunityRepository opportunities;
    @Autowired
    JdbcTemplate jdbc;

    FixtureServer server;
    Session admin;

    static byte[] pdf(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.setLeading(14);
                cs.newLineAtOffset(50, 700);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        admin = api().platformAdmin(jdbc);
        server = new FixtureServer().route("/robots.txt", 404, "text/plain", "");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void extractsRequirementsSubmissionAndClosingDateFromPdf() throws Exception {
        String marker = "doc" + Long.toString(System.nanoTime(), 36);
        server.html("/adverts", """
                <div id="available-tenders"><a href="/files/%s.pdf"><div class="text-dark">EOI Advert for Conveyor Splicing %s</div></a></div>
                """.formatted(marker, marker));
        int year = LocalDate.now().getYear() + 1;
        byte[] pdfBytes = pdf("EXPRESSION OF INTEREST - TEST DATA ONLY",
                "Respondents must have a CIDB grading of 5ME or higher.",
                "Respondents must be registered on the Central Supplier Database (CSD).",
                "Responses must be submitted by e-mail to the address below.",
                "Closing date: 15 March " + year + " at 12:00.");
        server.routes().put("/files/" + marker + ".pdf", new FixtureServer.BinaryResponse(200, "application/pdf", pdfBytes));

        Map<String, Object> req = new HashMap<>();
        req.put("name", "PDF fixture " + marker);
        req.put("sourceUrl", server.url("/adverts"));
        req.put("sourceType", "DOCUMENT_LISTING");
        req.put("sector", "MINING");
        req.put("accessMethod", "PUBLIC_HTML");
        req.put("integrationStatus", "ACTIVE_PUBLIC");
        req.put("enabled", true);
        req.put("robotsPolicy", "NOT_PRESENT");
        req.put("termsReviewStatus", "APPROVED");
        req.put("rateLimitPerMinute", 120);
        req.put("adapterType", "HTML_LISTING");
        req.put("reliability", "MEDIUM");
        req.put("priority", 50);
        req.put("parserConfig", """
                {"listContainerSelector": "#available-tenders", "itemSelector": "#available-tenders a[href]",
                 "fields": {"title": {"selector": ".text-dark"}, "documentUrl": {"attr": "href"}},
                 "defaultEventType": "OTHER", "eventTypeKeywords": {"EOI": "EOI"}}""");
        UUID sourceId = UUID.fromString(api().read(api().post("/api/v1/admin/sources", admin, req)
                .andExpect(status().isCreated())).get("id").asString());
        assertThat(crawlService.crawlNow(sourceId, TriggerType.MANUAL, null).getOutcome()).isEqualTo(CrawlOutcome.SUCCESS);

        Opportunity before = opportunities.findBySourceIdAndSourceReference(sourceId, server.url("/files/" + marker + ".pdf")).orElseThrow();
        assertThat(before.getClosingDate()).isNull();
        assertThat(before.getSubmissionMethod()).isEqualTo(SubmissionMethod.NOT_SPECIFIED);

        processing.processOpportunity(before.getId());

        Opportunity after = opportunities.findById(before.getId()).orElseThrow();
        assertThat(after.getClosingDate()).isEqualTo(LocalDate.of(year, 3, 15));
        assertThat(after.getClosingProvenance()).isEqualTo(Provenance.DOCUMENT);
        assertThat(after.getSubmissionMethod()).isEqualTo(SubmissionMethod.ELECTRONIC);
        assertThat(after.getSubmissionProvenance()).isEqualTo(Provenance.DOCUMENT);

        api().get("/api/v1/opportunities/" + after.getId() + "/requirements", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'CIDB_GRADING')].value").value("5ME"))
                .andExpect(jsonPath("$[?(@.type == 'CSD_REGISTRATION')].provenance").value("DOCUMENT"));

        JsonNode docs = api().read(api().get("/api/v1/opportunities/" + after.getId() + "/documents", admin));
        assertThat(docs.get(0).get("processingStatus").asString()).isEqualTo("EXTRACTED");
        String stored = docs.get(0).get("storedDocumentId").asString();
        byte[] original = mvc.perform(get("/api/v1/documents/" + stored + "/original")
                        .header("Authorization", "Bearer " + admin.accessToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(original).isEqualTo(pdfBytes);

        // Document text is searchable from the catalogue.
        api().get("/api/v1/opportunities/search?q=splicing " + marker, admin).andExpect(jsonPath("$.totalElements").value(1));

        // A re-crawl of the listing (which has no closing date) keeps the document-derived values.
        crawlService.crawlNow(sourceId, TriggerType.MANUAL, null);
        Opportunity recrawled = opportunities.findById(before.getId()).orElseThrow();
        assertThat(recrawled.getClosingDate()).isEqualTo(LocalDate.of(year, 3, 15));
        assertThat(recrawled.getSubmissionMethod()).isEqualTo(SubmissionMethod.ELECTRONIC);
    }

    @Test
    void companyDocumentsAreTenantScopedAndValidated() throws Exception {
        Session a = api().registerTenant("Docs A");
        Session b = api().registerTenant("Docs B");
        byte[] capability = pdf("TEST capability statement: Java and Angular development, systems integration.");

        JsonNode uploaded = api().read(mvc.perform(multipart("/api/v1/companies/documents")
                        .file(new MockMultipartFile("file", "capability.pdf", "application/pdf", capability))
                        .param("documentType", "CAPABILITY_STATEMENT")
                        .header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isCreated()));
        assertThat(uploaded.get("extractionStatus").asString()).isEqualTo("EXTRACTED");
        String id = uploaded.get("id").asString();

        mvc.perform(get("/api/v1/companies/documents/" + id).header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/companies/documents/" + id).header("Authorization", "Bearer " + b.accessToken()))
                .andExpect(status().isNotFound());

        // An executable disguised as a PDF is rejected by content detection.
        mvc.perform(multipart("/api/v1/companies/documents")
                        .file(new MockMultipartFile("file", "evil.pdf", "application/pdf", new byte[]{'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0}))
                        .header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isUnprocessableContent());
        assertThat(MediaType.APPLICATION_PDF).isNotNull();
    }
}
