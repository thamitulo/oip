package za.co.oip.focus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.execution.CrawlRecords.TriggerType;
import za.co.oip.crawling.execution.CrawlService;
import za.co.oip.opportunities.domain.Opportunity;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;
import za.co.oip.opportunities.domain.OpportunityRepository;
import za.co.oip.support.FixtureServer;
import za.co.oip.support.IntegrationTest;
import za.co.oip.support.TestApi.Session;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * State-farm lease adverts parsed with the seeded DLRRD configuration (served from a fixture with the same markup;
 * synthetic farm names), and "My focus" built on top of them.
 */
class FocusIT extends IntegrationTest {

    @Autowired
    CrawlService crawlService;
    @Autowired
    OpportunityRepository opportunities;
    @Autowired
    FocusNotificationJob focusJob;
    @Autowired
    JdbcTemplate jdbc;

    FixtureServer server;
    Session admin;
    String marker;

    @BeforeEach
    void setUp() throws Exception {
        marker = "tf" + Long.toString(System.nanoTime(), 36);
        server = new FixtureServer().route("/robots.txt", 404, "text/html", "not found").html("/state-farms", """
                <div class="uk-article"><div class="uk-margin-top" property="text"><ul>
                <li><a href="/images/Docs/declaration.pdf">Declaration of applicant for Land Allocation in Terms of the State Land Lease and Disposal Policy</a></li>
                <li><a href="/images/farms/%1$s-a.pdf">Portion 6 of the farm %1$s 508 IS in the Limpopo Province - Closing: 27 November 2099 at 16h00.</a></li>
                <li><a href="/images/farms/%1$s-b.pdf">Remaining Extent of the farm %1$s Old 12 JR - Closing date: 14 November 2020</a></li>
                <li><a href="/images/farms/%1$s-c.pdf">Marah Farm %1$s - Closing Date: 10 October 2099 at 16h00 (site briefing will be held on 26 September 2099 at 10h00)</a></li>
                </ul></div></div>""".formatted(marker));
        admin = api().platformAdmin(jdbc);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void seededDlrrdConfigurationParsesStateFarmAdverts() throws Exception {
        String config = jdbc.queryForObject("select parser_config::text from source_registry where id = 'c0000000-0000-0000-0000-000000000040'",
                String.class);
        Map<String, Object> req = new HashMap<>();
        req.put("name", "DLRRD fixture " + marker);
        req.put("sourceUrl", server.url("/state-farms"));
        req.put("sourceType", "DOCUMENT_LISTING");
        req.put("sector", "GOVERNMENT");
        req.put("accessMethod", "PUBLIC_HTML");
        req.put("integrationStatus", "ACTIVE_PUBLIC");
        req.put("robotsPolicy", "NOT_PRESENT");
        req.put("termsReviewStatus", "APPROVED");
        req.put("rateLimitPerMinute", 120);
        req.put("adapterType", "HTML_LISTING");
        req.put("reliability", "MEDIUM");
        req.put("priority", 50);
        req.put("parserConfig", config);
        UUID sourceId = UUID.fromString(api().read(api().post("/api/v1/admin/sources", admin, req)
                .andExpect(status().isCreated())).get("id").asString());

        var run = crawlService.crawlNow(sourceId, TriggerType.MANUAL, null);
        assertThat(run.getOutcome()).isEqualTo(CrawlOutcome.SUCCESS);
        assertThat(run.getRecordsFound()).isEqualTo(3); // the application form has no closing date and is skipped
        assertThat(run.getRecordsRejected()).isZero();

        Opportunity open = opportunities.findBySourceIdAndSourceReference(sourceId, server.url("/images/farms/" + marker + "-a.pdf")).orElseThrow();
        assertThat(open.getEventType()).isEqualTo(EventType.LAND_LEASE);
        assertThat(open.getProvince()).isEqualTo("LIMPOPO");
        assertThat(open.getClosingDate()).isEqualTo(LocalDate.of(2099, 11, 27));
        assertThat(open.getClosingAt()).isEqualTo(Instant.parse("2099-11-27T14:00:00Z")); // 16h00 SAST
        assertThat(open.getStatus()).isEqualTo(OpportunityStatus.OPEN);

        Opportunity old = opportunities.findBySourceIdAndSourceReference(sourceId, server.url("/images/farms/" + marker + "-b.pdf")).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(OpportunityStatus.EXPIRED);

        Opportunity briefing = opportunities.findBySourceIdAndSourceReference(sourceId, server.url("/images/farms/" + marker + "-c.pdf")).orElseThrow();
        assertThat(briefing.getBriefingDetails()).contains("site briefing");
    }

    @Test
    void focusAreaFindsStateLandAndNotifiesOnce() throws Exception {
        seededDlrrdConfigurationParsesStateFarmAdverts();
        Session user = api().registerTenant("Farming Co " + marker);

        JsonNode templates = api().read(api().get("/api/v1/focus/templates", user).andExpect(status().isOk()));
        JsonNode template = templates.get(0);
        assertThat(template.get("code").asString()).isEqualTo("STATE_LAND_LEASE");
        assertThat(template.get("guidance").get(0).get("url").asString()).startsWith("https://www.dlrrd.gov.za/");

        Map<String, Object> focus = new HashMap<>();
        focus.put("name", "State land to lease");
        focus.put("templateCode", "STATE_LAND_LEASE");
        focus.put("keywords", List.of(marker));
        focus.put("eventTypes", List.of());
        focus.put("provinces", List.of("Limpopo"));
        JsonNode created = api().read(api().post("/api/v1/focus", user, focus).andExpect(status().isCreated()));
        String focusId = created.get("id").asString();
        assertThat(created.get("provinces").get(0).asString()).isEqualTo("LIMPOPO");
        assertThat(created.get("guidance").size()).isEqualTo(2);

        // Only the open advert (closed and briefing ones: closed is excluded; the Marah one has no province so it is kept).
        JsonNode results = api().read(api().get("/api/v1/focus/" + focusId + "/opportunities", user).andExpect(status().isOk()));
        List<String> titles = new java.util.ArrayList<>();
        results.get("content").forEach(n -> titles.add(n.get("title").asString()));
        assertThat(titles).anyMatch(t -> t.contains(marker + " 508 IS")).noneMatch(t -> t.contains("Old 12 JR"));

        // Open Limpopo advert + open advert without a published province; never the closed one; never twice.
        focusJob.run();
        String countSql = "select count(*) from notifications where user_id = ? and type = 'FOCUS_MATCH'";
        assertThat(jdbc.queryForObject(countSql, Integer.class, user.userId())).isEqualTo(2);
        focusJob.run();
        assertThat(jdbc.queryForObject(countSql, Integer.class, user.userId())).isEqualTo(2);

        // Personal to the user: another tenant cannot see it.
        Session other = api().registerTenant("Other Co " + marker);
        api().get("/api/v1/focus/" + focusId + "/opportunities", other).andExpect(status().isNotFound());

        // A focus needs at least one keyword or type.
        api().post("/api/v1/focus", user, Map.of("name", "Empty")).andExpect(status().isUnprocessableContent());
        api().get("/api/v1/focus", user).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void focusRanksTitleMatchesAndKeepsDocumentOnlyMatchesOptIn() throws Exception {
        seededDlrrdConfigurationParsesStateFarmAdverts();
        Session user = api().registerTenant("Precise Co " + marker);
        Opportunity marah = opportunities.findAll().stream()
                .filter(o -> o.getTitle() != null && o.getTitle().contains("Marah Farm " + marker)).findFirst().orElseThrow();
        // A word that appears only inside the Marah advert's document text (boilerplate-style match).
        String docWord = marker + "boilerplate";
        UUID docId = UUID.randomUUID();
        jdbc.update("insert into opportunity_documents (id, opportunity_id, url, first_seen_at, last_seen_at) values (?, ?, ?, now(), now())",
                docId, marah.getId(), server.url("/docs/" + marker + ".pdf"));
        jdbc.update("insert into source_documents (id, opportunity_document_id, opportunity_id, status, text_content, created_at, updated_at)"
                + " values (?, ?, ?, 'EXTRACTED', ?, now(), now())", UUID.randomUUID(), docId, marah.getId(), "Terms apply " + docWord + ".");

        Map<String, Object> focus = new HashMap<>();
        focus.put("name", "Docs");
        focus.put("keywords", List.of(docWord));
        String id = api().read(api().post("/api/v1/focus", user, focus).andExpect(status().isCreated())).get("id").asString();
        api().get("/api/v1/focus/" + id + "/opportunities", user).andExpect(jsonPath("$.totalElements").value(0));

        focus.put("searchDocuments", true);
        api().put("/api/v1/focus/" + id, user, focus).andExpect(status().isOk()).andExpect(jsonPath("$.searchDocuments").value(true));
        api().get("/api/v1/focus/" + id + "/opportunities", user)
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(marah.getId().toString()));

        // Title matches rank above document-only matches; excluded words remove a result.
        // Marah closes sooner, but the title match ranks above it.
        focus.put("keywords", List.of(docWord, marker + " 508"));
        api().put("/api/v1/focus/" + id, user, focus).andExpect(status().isOk());
        JsonNode ranked = api().read(api().get("/api/v1/focus/" + id + "/opportunities", user));
        assertThat(ranked.get("totalElements").asInt()).isEqualTo(2);
        assertThat(ranked.get("content").get(0).get("title").asString()).contains(marker + " 508 IS");
        assertThat(ranked.get("content").get(1).get("id").asString()).isEqualTo(marah.getId().toString());
        focus.put("keywords", List.of(docWord, marker));
        focus.put("excludeKeywords", List.of("Marah"));
        api().put("/api/v1/focus/" + id, user, focus).andExpect(status().isOk());
        JsonNode excluded = api().read(api().get("/api/v1/focus/" + id + "/opportunities", user));
        assertThat(excluded.get("totalElements").asInt()).isEqualTo(1);
        assertThat(excluded.get("content").get(0).get("title").asString()).contains(marker + " 508 IS");

        // Acronyms that are English stop words ("IS") still match, case-sensitively, as whole words.
        Map<String, Object> acronym = new HashMap<>();
        acronym.put("name", "Acronym");
        acronym.put("keywords", List.of("IS"));
        String acrId = api().read(api().post("/api/v1/focus", user, acronym).andExpect(status().isCreated())).get("id").asString();
        List<String> titles = new java.util.ArrayList<>();
        api().read(api().get("/api/v1/focus/" + acrId + "/opportunities?size=100", user)).get("content")
                .forEach(n -> titles.add(n.get("title").asString()));
        assertThat(titles).anyMatch(t -> t.contains(marker + " 508 IS"));
    }

    @Test
    void seededSourceIsRegisteredWithVerificationNotes() {
        Map<String, Object> s = jdbc.queryForMap("select enabled, integration_status, adapter_type, terms_review_status, robots_policy, "
                + "licence_notes, verification_notes from source_registry where id = 'c0000000-0000-0000-0000-000000000040'");
        // Enabled once the crawler could validate the site's certificate chain with the reviewed intermediate.
        assertThat(s.get("enabled")).isEqualTo(true);
        assertThat(s.get("integration_status")).isEqualTo("ACTIVE_PUBLIC");
        assertThat((String) s.get("verification_notes")).contains("Sectigo Public Server Authentication CA OV R36");
        assertThat(s.get("adapter_type")).isEqualTo("HTML_LISTING");
        assertThat(s.get("robots_policy")).isEqualTo("NOT_PRESENT");
        assertThat((String) s.get("licence_notes")).contains("no terms of use");
    }
}
