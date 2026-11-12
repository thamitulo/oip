package za.co.oip.opportunities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.execution.CrawlRecords.TriggerType;
import za.co.oip.crawling.execution.CrawlService;
import za.co.oip.support.FixtureServer;
import za.co.oip.support.IntegrationTest;
import za.co.oip.support.TestApi.Session;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Catalogue search, filters and detail over crawled (synthetic) fixture data. */
class OpportunityCatalogueIT extends IntegrationTest {

    @Autowired
    CrawlService crawlService;
    @Autowired
    JdbcTemplate jdbc;

    FixtureServer server;
    Session admin;
    UUID sourceId;
    String marker;

    @BeforeEach
    void setUp() throws Exception {
        marker = "zq" + Long.toString(System.nanoTime(), 36);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
        String soon = LocalDate.now().plusDays(2).format(fmt);
        String later = LocalDate.now().plusDays(40).format(fmt);
        server = new FixtureServer().route("/robots.txt", 404, "text/plain", "")
                .html("/list", """
                        <div id="available-tenders">
                          <a href="/docs/%1$s-eoi.pdf"><div class="text-dark">EOI Advert for Splicing %1$s</div><span class="c">%2$s</span></a>
                          <a href="/docs/%1$s-rfq.pdf"><div class="text-dark">RFQ Supply of Filters %1$s</div><span class="c">%3$s</span></a>
                        </div>""".formatted(marker, soon, later));
        admin = api().platformAdmin(jdbc);

        Map<String, Object> req = new HashMap<>();
        req.put("name", "Catalogue fixture " + marker);
        req.put("organisationId", "a0000000-0000-0000-0000-000000000008");
        req.put("sourceUrl", server.url("/list"));
        req.put("sourceType", "DOCUMENT_LISTING");
        req.put("sector", "MINING");
        req.put("accessMethod", "PUBLIC_HTML");
        req.put("integrationStatus", "ACTIVE_PUBLIC");
        req.put("robotsPolicy", "NOT_PRESENT");
        req.put("termsReviewStatus", "APPROVED");
        req.put("rateLimitPerMinute", 120);
        req.put("adapterType", "HTML_LISTING");
        req.put("reliability", "MEDIUM");
        req.put("priority", 50);
        req.put("markVerified", true);
        req.put("parserConfig", """
                {"listContainerSelector": "#available-tenders", "itemSelector": "#available-tenders a[href]",
                 "fields": {"title": {"selector": ".text-dark"}, "documentUrl": {"attr": "href"},
                            "closingDate": {"selector": ".c"}},
                 "dateFormats": ["d MMM yyyy"], "defaultEventType": "OTHER",
                 "eventTypeKeywords": {"EOI": "EOI", "RFQ": "RFQ"}}""");
        JsonNode created = api().read(api().post("/api/v1/admin/sources", admin, req).andExpect(status().isCreated()));
        sourceId = UUID.fromString(created.get("id").asString());
        assertThat(crawlService.crawlNow(sourceId, TriggerType.MANUAL, null).getOutcome()).isEqualTo(CrawlOutcome.SUCCESS);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void searchFilterAndDetail() throws Exception {
        Session user = api().registerTenant("Catalogue Reader Co");

        api().get("/api/v1/opportunities/search?q=" + marker, user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        JsonNode mining = api().read(api().get("/api/v1/opportunities?sector=MINING&sourceId=" + sourceId
                + "&eventType=EOI", user).andExpect(status().isOk()));
        assertThat(mining.get("totalElements").asInt()).isEqualTo(1);
        JsonNode card = mining.get("content").get(0);
        assertThat(card.get("organisationName").asString()).isEqualTo("Exxaro Resources");
        assertThat(card.get("sourceVerified").asBoolean()).isTrue();
        assertThat(card.get("urgency").asString()).isEqualTo("URGENT");
        assertThat(card.get("sourceUrl").asString()).isEqualTo(server.url("/docs/" + marker + "-eoi.pdf"));

        api().get("/api/v1/opportunities/closing-soon?days=7&sector=MINING", user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.sourceId == '" + sourceId + "')]").isNotEmpty());

        api().get("/api/v1/opportunities?sector=GOVERNMENT&sourceId=" + sourceId, user)
                .andExpect(jsonPath("$.totalElements").value(0));

        String id = card.get("id").asString();
        api().get("/api/v1/opportunities/" + id, user)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.evidenceUrl").value(server.url("/list")))
                .andExpect(jsonPath("$.evidence.firstDiscoveredAt").isNotEmpty())
                .andExpect(jsonPath("$.documents[0].fileFormat").value("pdf"))
                .andExpect(jsonPath("$.estimatedValue").doesNotExist());

        api().get("/api/v1/opportunities/" + UUID.randomUUID(), user).andExpect(status().isNotFound());
        api().get("/api/v1/opportunities", null).andExpect(status().isUnauthorized());
    }

    @Test
    void twoTenantsSeeTheSameSingleGlobalOpportunity() throws Exception {
        Session a = api().registerTenant("Tenant One");
        Session b = api().registerTenant("Tenant Two");
        String q = "/api/v1/opportunities?sourceId=" + sourceId + "&eventType=RFQ";
        String idA = api().read(api().get(q, a)).get("content").get(0).get("id").asString();
        String idB = api().read(api().get(q, b)).get("content").get(0).get("id").asString();
        assertThat(idA).isEqualTo(idB);
        assertThat(jdbc.queryForObject("select count(*) from opportunities where source_id = ?", Integer.class, sourceId))
                .isEqualTo(2);
    }

    @Test
    void dashboardUsesPersistedData() throws Exception {
        Session user = api().registerTenant("Dashboard Co");
        JsonNode d = api().read(api().get("/api/v1/analytics/dashboard", user).andExpect(status().isOk()));
        assertThat(d.get("activeOpportunities").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(d.get("miningActive").asLong()).isGreaterThanOrEqualTo(2);
    }
}
