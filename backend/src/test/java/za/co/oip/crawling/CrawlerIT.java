package za.co.oip.crawling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import za.co.oip.crawling.execution.CrawlRecords.CrawlJobRun;
import za.co.oip.crawling.execution.CrawlRecords.TriggerType;
import za.co.oip.crawling.execution.CrawlService;
import za.co.oip.opportunities.domain.Opportunity;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;
import za.co.oip.opportunities.domain.OpportunityRepository;
import za.co.oip.opportunities.domain.SourceChangeEventRepository;
import za.co.oip.support.FixtureServer;
import za.co.oip.support.IntegrationTest;
import za.co.oip.support.TestApi.Session;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end crawler behaviour against a local fixture server: adapters, ingestion, de-duplication,
 * change detection and failure classification. All listings here are synthetic test data.
 */
class CrawlerIT extends IntegrationTest {

    @Autowired
    CrawlService crawlService;
    @Autowired
    OpportunityRepository opportunities;
    @Autowired
    SourceChangeEventRepository changes;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate tx;

    FixtureServer server;
    Session admin;

    static String listing(String... rows) {
        return """
                <html><body><table class="public-opportunity-table"><tbody>%s</tbody></table></body></html>
                """.formatted(String.join("", rows));
    }

    static String row(String ref, String title, String status, String closes) {
        return """
                <tr><td data-label="Reference">%s</td>
                <td data-label="Title"><a href="/tenders/%s">%s</a></td>
                <td data-label="Status">%s</td><td data-label="Location">Test Mine, Limpopo</td>
                <td data-label="Category">Mining Services</td><td data-label="Closes">%s</td></tr>
                """.formatted(ref, ref, title, status, closes);
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new FixtureServer().route("/robots.txt", 200, "text/plain", "User-agent: *\nDisallow: /private\n");
        admin = api().platformAdmin(jdbc);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    UUID createHtmlSource(String path) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("name", "Fixture source " + UUID.randomUUID());
        req.put("sourceUrl", server.url(path));
        req.put("sourceType", "TENDER_LISTING");
        req.put("sector", "MINING");
        req.put("accessMethod", "PUBLIC_HTML");
        req.put("integrationStatus", "ACTIVE_PUBLIC");
        req.put("enabled", true);
        req.put("robotsPolicy", "ALLOWED");
        req.put("termsReviewStatus", "APPROVED");
        req.put("rateLimitPerMinute", 120);
        req.put("adapterType", "HTML_LISTING");
        req.put("reliability", "MEDIUM");
        req.put("priority", 50);
        req.put("parserConfig", """
                {"listContainerSelector": "table.public-opportunity-table",
                 "itemSelector": "table.public-opportunity-table tbody tr",
                 "fields": {"referenceNumber": {"selector": "td[data-label=Reference]"},
                            "title": {"selector": "td[data-label=Title] a"},
                            "detailUrl": {"selector": "td[data-label=Title] a", "attr": "href"},
                            "status": {"selector": "td[data-label=Status]"},
                            "location": {"selector": "td[data-label=Location]"},
                            "category": {"selector": "td[data-label=Category]"},
                            "closingDate": {"selector": "td[data-label=Closes]"}},
                 "dateFormats": ["d MMM yyyy"], "defaultEventType": "RFQ"}
                """);
        JsonNode created = api().read(api().post("/api/v1/admin/sources", admin, req).andExpect(status().isCreated()));
        return UUID.fromString(created.get("id").asString());
    }

    CrawlJobRun crawl(UUID sourceId) {
        return crawlService.crawlNow(sourceId, TriggerType.MANUAL, null);
    }

    @Test
    void discoversOpportunitiesAndDoesNotDuplicateOnRecrawl() throws Exception {
        String future = LocalDate.now().plusDays(20).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH));
        server.html("/tenders", listing(row("TEST-RFQ-001", "TEST Conveyor belt maintenance", "Open", future),
                row("TEST-RFQ-002", "TEST Security services", "Closed", "24 Sept 2026")));
        UUID sourceId = createHtmlSource("/tenders");

        CrawlJobRun first = crawl(sourceId);
        assertThat(first.getOutcome()).isEqualTo(CrawlOutcome.SUCCESS);
        assertThat(first.getRecordsDiscovered()).isEqualTo(2);

        CrawlJobRun second = crawl(sourceId);
        assertThat(second.getOutcome()).isEqualTo(CrawlOutcome.NO_CHANGE);
        assertThat(second.getRecordsUnchanged()).isEqualTo(2);
        assertThat(opportunities.countBySourceId(sourceId)).isEqualTo(2);

        Opportunity o = opportunities.findBySourceIdAndSourceReference(sourceId, "TEST-RFQ-001").orElseThrow();
        assertThat(o.getSourceUrl()).isEqualTo(server.url("/tenders/TEST-RFQ-001"));
        assertThat(o.getEvidenceUrl()).isEqualTo(server.url("/tenders"));
        assertThat(o.getProvince()).isNull(); // "Test Mine, Limpopo" is not a bare province; nothing is guessed
        assertThat(o.isClosingTimeSpecified()).isFalse();
        assertThat(o.getStatus()).isEqualTo(OpportunityStatus.OPEN);
        assertThat(o.isStatusInferred()).isFalse();

        // Evidence of the fetch is retained
        Integer observations = jdbc.queryForObject("select count(*) from source_observations where run_id = ?",
                Integer.class, first.getId());
        assertThat(observations).isEqualTo(1);
    }

    @Test
    void closingDateChangeIsRecordedAsMaterialChange() throws Exception {
        server.html("/changing", listing(row("TEST-CHG-1", "TEST Pump refurbishment", "Open", "10 Dec 2099")));
        UUID sourceId = createHtmlSource("/changing");
        crawl(sourceId);

        server.html("/changing", listing(row("TEST-CHG-1", "TEST Pump refurbishment", "Open", "17 Dec 2099")));
        CrawlJobRun run = crawl(sourceId);

        assertThat(run.getOutcome()).isEqualTo(CrawlOutcome.SUCCESS);
        assertThat(run.getRecordsUpdated()).isEqualTo(1);
        Opportunity o = opportunities.findBySourceIdAndSourceReference(sourceId, "TEST-CHG-1").orElseThrow();
        assertThat(o.getClosingDate()).isEqualTo(LocalDate.of(2099, 12, 17));
        var events = changes.findByOpportunityIdOrderByDetectedAtDesc(o.getId());
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getFieldName()).isEqualTo("closingDate");
            assertThat(e.getOldValue()).isEqualTo("2099-12-10");
            assertThat(e.getNewValue()).isEqualTo("2099-12-17");
            assertThat(e.isMaterial()).isTrue();
        });

        api().get("/api/v1/opportunities/" + o.getId() + "/history", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fieldName").value("closingDate"));
    }

    @Test
    void emptyListingIsZeroResultsButErrorPageIsParserFailure() throws Exception {
        server.html("/empty", listing());
        UUID empty = createHtmlSource("/empty");
        assertThat(crawl(empty).getOutcome()).isEqualTo(CrawlOutcome.SUCCESS_ZERO_RESULTS);

        // HTTP 200 whose body is a server error page (as observed on a real source) must not look empty.
        server.html("/broken", "<html><body>Fatal error: Uncaught mysqli_sql_exception: Access denied</body></html>");
        UUID broken = createHtmlSource("/broken");
        CrawlJobRun run = crawl(broken);
        assertThat(run.getOutcome()).isEqualTo(CrawlOutcome.PARSER_FAILED);
        assertThat(run.getStatus().name()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from crawl_errors where run_id = ?", Integer.class, run.getId()))
                .isPositive();
    }

    @Test
    void unavailableSourceFailsWithoutTouchingExistingOpportunities() throws Exception {
        server.html("/flaky", listing(row("TEST-FLK-1", "TEST Diesel supply", "Open", "1 Jan 2099")));
        UUID sourceId = createHtmlSource("/flaky");
        crawl(sourceId);

        server.route("/flaky", 503, "text/plain", "down for maintenance");
        CrawlJobRun run = crawl(sourceId);

        assertThat(run.getOutcome()).isEqualTo(CrawlOutcome.SOURCE_UNAVAILABLE);
        assertThat(run.getHttpStatus()).isEqualTo(503);
        Opportunity o = opportunities.findBySourceIdAndSourceReference(sourceId, "TEST-FLK-1").orElseThrow();
        assertThat(o.getStatus()).isEqualTo(OpportunityStatus.OPEN);
        Integer failures = jdbc.queryForObject("select consecutive_failures from crawl_schedules where source_id = ?",
                Integer.class, sourceId);
        assertThat(failures).isEqualTo(1);
    }

    @Test
    void loginGatedAndRobotsDisallowedSourcesAreClassified() throws Exception {
        server.route("/login-only", 401, "text/plain", "login required");
        assertThat(crawl(createHtmlSource("/login-only")).getOutcome()).isEqualTo(CrawlOutcome.AUTHENTICATION_REQUIRED);

        server.html("/private/tenders", listing(row("TEST-PRV-1", "TEST", "Open", "1 Jan 2099")));
        UUID blocked = createHtmlSource("/private/tenders");
        assertThat(crawl(blocked).getOutcome()).isEqualTo(CrawlOutcome.BLOCKED_BY_ROBOTS);
        assertThat(server.hits("/private/tenders")).isZero();
    }

    @Test
    void rowsThatAllFailToParseAreAParserFailure() throws Exception {
        server.html("/bad-dates", listing(row("TEST-BAD-1", "TEST Item", "Open", "not a date")));
        CrawlJobRun run = crawl(createHtmlSource("/bad-dates"));
        assertThat(run.getOutcome()).isEqualTo(CrawlOutcome.PARSER_FAILED);
    }

    @Test
    void expiredOpportunitiesAreMarkedExpired() throws Exception {
        server.html("/expiry", listing(row("TEST-EXP-1", "TEST Expiring item", "", "1 Jan 2099")));
        UUID sourceId = createHtmlSource("/expiry");
        crawl(sourceId);
        Opportunity o = opportunities.findBySourceIdAndSourceReference(sourceId, "TEST-EXP-1").orElseThrow();
        assertThat(o.getStatus()).isEqualTo(OpportunityStatus.OPEN);
        assertThat(o.isStatusInferred()).isTrue();

        // Closing date passes: the lifecycle job's query expires it.
        jdbc.update("update opportunities set closing_date = ? where id = ?", LocalDate.now().minusDays(2), o.getId());
        Integer expired = tx.execute(st -> opportunities.expireClosed(java.time.Instant.now(), LocalDate.now()));
        assertThat(expired).isEqualTo(1);
        assertThat(opportunities.findById(o.getId()).orElseThrow().getStatus()).isEqualTo(OpportunityStatus.EXPIRED);
    }

    @Test
    void manualCrawlApiAndSourceEnablingRules() throws Exception {
        server.html("/api-crawl", listing(row("TEST-API-1", "TEST Item", "Open", "1 Jan 2099")));
        UUID sourceId = createHtmlSource("/api-crawl");

        api().post("/api/v1/admin/sources/" + sourceId + "/crawl", admin, null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNotEmpty());

        JsonNode runs = null;
        for (int i = 0; i < 50; i++) {
            runs = api().read(api().get("/api/v1/admin/crawls?sourceId=" + sourceId, admin).andExpect(status().isOk()));
            if (runs.get("content").size() > 0 && runs.get("content").get(0).get("finishedAt") != null
                    && !runs.get("content").get(0).get("finishedAt").isNull()) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(runs.get("content").get(0).get("outcome").asString()).isEqualTo("SUCCESS");

        // Seeded source that is pending verification cannot be enabled or crawled.
        String pending = "c0000000-0000-0000-0000-000000000011";
        api().post("/api/v1/admin/sources/" + pending + "/crawl", admin, null).andExpect(status().isUnprocessableContent());

        JsonNode source = api().read(api().get("/api/v1/admin/sources/" + pending, admin).andExpect(status().isOk()));
        assertThat(source.get("crawlBlockers").toString()).contains("PENDING_VERIFICATION");

        api().get("/api/v1/admin/source-health", admin).andExpect(status().isOk());
        api().post("/api/v1/admin/sources/" + sourceId + "/test", admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.recordsFound").value(1));
    }

    @Test
    void seededRegisterIsHonestAboutVerification() {
        List<Map<String, Object>> enabled = jdbc.queryForList("select name from source_registry where enabled and id::text like 'c0000000%'");
        assertThat(enabled).extracting(m -> m.get("name")).containsExactlyInAnyOrder("eTenders (National Treasury)", "Tharisa opportunity portal",
                "DLRRD state farms to lease");
        Integer opportunitiesSeeded = jdbc.queryForObject(
                "select count(*) from opportunities o join source_registry s on s.id = o.source_id where s.id::text like 'c0000000%'",
                Integer.class);
        assertThat(opportunitiesSeeded).isZero();
    }
}
