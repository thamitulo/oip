package za.co.oip.pipeline;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import za.co.oip.companies.CompanyProfileIT;
import za.co.oip.notifications.NotificationService;
import za.co.oip.support.IntegrationTest;
import za.co.oip.support.TestApi.Session;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Pipeline, matching, notifications, privacy and AI availability, over a synthetic opportunity. */
class PipelineAndNotificationsIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    NotificationService notifications;

    UUID opportunityId;

    @BeforeEach
    void seedOpportunity() {
        opportunityId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into opportunities (id, source_id, source_reference, reference_number, title, description, event_type, status,
                  province, closing_date, source_url, content_hash, first_discovered_at, last_seen_at, last_checked_at, created_at, updated_at)
                values (?, 'c0000000-0000-0000-0000-000000000001', ?, 'TEST/001', 'TEST DATA Software development of a workflow system',
                  'Software development and systems integration', 'TENDER', 'OPEN', 'GAUTENG', ?, 'https://example.test/doc.pdf', 'h', ?, ?, ?, ?, ?)""",
                opportunityId, "test-" + opportunityId, LocalDate.now().plusDays(10), now, now, now, now, now);
    }

    @Test
    void tenantsTrackTheSameOpportunityIndependently() throws Exception {
        Session a = api().registerTenant("Pipeline A");
        Session b = api().registerTenant("Pipeline B");

        api().post("/api/v1/tenant/opportunities/" + opportunityId + "/shortlist", a, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("SHORTLISTED"));
        api().post("/api/v1/tenant/opportunities/" + opportunityId + "/shortlist", b, Map.of()).andExpect(status().isOk());
        api().put("/api/v1/tenant/opportunities/" + opportunityId + "/status", a, Map.of("stage", "BID_IN_PROGRESS"))
                .andExpect(jsonPath("$.stage").value("BID_IN_PROGRESS"));
        api().post("/api/v1/tenant/opportunities/" + opportunityId + "/notes", a, Map.of("body", "Confidential pricing strategy"))
                .andExpect(status().isCreated());

        JsonNode bDetail = api().read(api().get("/api/v1/tenant/opportunities/" + opportunityId, b).andExpect(status().isOk()));
        assertThat(bDetail.get("item").get("stage").asString()).isEqualTo("SHORTLISTED");
        assertThat(bDetail.get("notes")).isEmpty();
        // The global opportunity is unchanged by any tenant's pipeline.
        assertThat(jdbc.queryForObject("select status from opportunities where id = ?", String.class, opportunityId)).isEqualTo("OPEN");

        api().post("/api/v1/tenant/opportunities/" + opportunityId + "/decisions", a, Map.of("decision", "GO", "rationale", "Fits"))
                .andExpect(status().isCreated());
        api().get("/api/v1/tenant/opportunities/" + opportunityId, a)
                .andExpect(jsonPath("$.item.stage").value("DOCUMENTS_BEING_PREPARED"))
                .andExpect(jsonPath("$.history[0].eventType").exists());
    }

    @Test
    void assignmentNotifiesOnceAndOnlyTenantMembersCanBeAssigned() throws Exception {
        Session owner = api().registerTenant("Assign Co");
        Session outsider = api().registerTenant("Outsider Co");
        String email = "bm-" + System.nanoTime() + "@example.test";
        String bmId = api().read(api().post("/api/v1/users", owner, Map.of("email", email, "fullName", "Bid Manager",
                "role", "BID_MANAGER", "initialPassword", "bid-manager-pass"))).get("member").get("id").asString();
        Session bm = api().login(email, "bid-manager-pass");

        api().post("/api/v1/tenant/opportunities/" + opportunityId + "/shortlist", owner, Map.of());
        api().post("/api/v1/tenant/opportunities/" + opportunityId + "/assign", owner, Map.of("userId", outsider.userId().toString()))
                .andExpect(status().isUnprocessableContent());
        api().post("/api/v1/tenant/opportunities/" + opportunityId + "/assign", owner, Map.of("userId", bmId))
                .andExpect(jsonPath("$.assignedToName").value("Bid Manager"));

        api().get("/api/v1/notifications", bm).andExpect(jsonPath("$[0].type").value("ASSIGNED"));
        // Same dedup key → not created twice.
        assertThat(notifications.notify(owner.tenantId(), bm.userId(), NotificationService.Type.NEW_MATCH, "x", null, null, null, "k1")).isTrue();
        assertThat(notifications.notify(owner.tenantId(), bm.userId(), NotificationService.Type.NEW_MATCH, "x", null, null, null, "k1")).isFalse();
        api().get("/api/v1/notifications/unread-count", bm).andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void matchExplainsScoreForTheTenantProfile() throws Exception {
        Session s = api().registerTenant("Match Co");
        var profile = CompanyProfileIT.profile("Match Co (Pty) Ltd");
        profile.put("services", java.util.List.of("Software development", "Systems integration"));
        api().put("/api/v1/companies/profile", s, profile).andExpect(status().isOk());
        api().get("/api/v1/opportunities/" + opportunityId + "/match", s)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(org.hamcrest.Matchers.greaterThanOrEqualTo(60)))
                .andExpect(jsonPath("$.matched").isNotEmpty());
        api().put("/api/v1/companies/match-settings", s, Map.of("weightServices", 50, "weightIndustry", 10, "weightCapability", 10,
                "weightGeography", 10, "weightRegistrations", 10, "weightOther", 5, "strongThreshold", 70, "possibleThreshold", 45))
                .andExpect(status().isUnprocessableContent()); // sums to 95
    }

    @Test
    void aiFeaturesReportUnavailableWithoutAKey() throws Exception {
        Session s = api().registerTenant("AI Co");
        api().get("/api/v1/ai/status", s).andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.assistantAvailable").value(false));
        api().post("/api/v1/ai/assistant/messages", s, Map.of("message", "Find IT tenders"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void privacyExportAndAccountDeletion() throws Exception {
        Session s = api().registerTenant("Privacy Co");
        api().get("/api/v1/users/me/export", s).andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value(s.email()));
        api().post("/api/v1/users/me/delete", s, Map.of("password", "wrong")).andExpect(status().isUnauthorized());
        api().post("/api/v1/users/me/delete", s, Map.of("password", "correct-horse-battery")).andExpect(status().isNoContent());
        api().post("/api/v1/auth/login", null, Map.of("email", s.email(), "password", "correct-horse-battery"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshWorksFromTheHttpOnlyCookie() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "c-" + System.nanoTime() + "@example.test",
                                "password", "correct-horse-battery", "fullName", "Cookie", "companyName", "Cookie Co", "acceptTerms", true))))
                .andExpect(status().isCreated())
                .andExpect(cookie().httpOnly("oip_refresh", true))
                .andReturn();
        String token = result.getResponse().getCookie("oip_refresh").getValue();
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("oip_refresh", token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
    }
}
