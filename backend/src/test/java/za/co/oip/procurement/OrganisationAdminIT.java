package za.co.oip.procurement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import za.co.oip.support.IntegrationTest;
import za.co.oip.support.TestApi.Session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Platform administrators maintain organisations and their procurement channels without migrations. */
class OrganisationAdminIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    static Map<String, Object> org(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("organisationType", "MINING_COMPANY");
        m.put("sector", "MINING");
        m.put("website", "https://example.org");
        m.put("websiteVerification", "UNVERIFIED");
        m.put("notes", "Test organisation");
        return m;
    }

    @Test
    void adminCreatesOrganisationAndManagesChannels() throws Exception {
        Session admin = api().platformAdmin(jdbc);
        String name = "Test Mining Co " + UUID.randomUUID();
        JsonNode created = api().read(api().post("/api/v1/admin/organisations", admin, org(name)).andExpect(status().isCreated()));
        String id = created.get("organisation").get("id").asString();

        UUID platformId = jdbc.queryForObject("select id from supplier_platforms limit 1", UUID.class);
        Map<String, Object> channel = new HashMap<>(Map.of("channelType", "SUPPLIER_REGISTRATION", "name", "Supplier portal",
                "url", "https://example.org/suppliers", "verificationStatus", "PENDING_VERIFICATION"));
        channel.put("supplierPlatformId", platformId.toString());
        JsonNode withChannel = api().read(api().post("/api/v1/admin/organisations/" + id + "/channels", admin, channel)
                .andExpect(status().isCreated()));
        JsonNode c = withChannel.get("channels").get(0);
        assertThat(c.get("supplierPlatformId").asString()).isEqualTo(platformId.toString());

        // Editing keeps the platform when the client sends it back (as the UI does).
        channel.put("verificationStatus", "VERIFIED");
        JsonNode updated = api().read(api().put("/api/v1/admin/organisations/" + id + "/channels/" + c.get("id").asString(), admin, channel)
                .andExpect(status().isOk()));
        assertThat(updated.get("channels").get(0).get("verificationStatus").asString()).isEqualTo("VERIFIED");
        assertThat(updated.get("channels").get(0).get("supplierPlatformId").asString()).isEqualTo(platformId.toString());

        Map<String, Object> changed = org(name);
        changed.put("primaryCommodities", "Coal");
        api().put("/api/v1/admin/organisations/" + id, admin, changed).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from audit_events where entity_id = ? and action = 'ORGANISATION_UPDATED'",
                Integer.class, id)).isEqualTo(1);

        api().delete("/api/v1/admin/organisations/" + id + "/channels/" + c.get("id").asString(), admin).andExpect(status().isNoContent());
        assertThat(api().read(api().get("/api/v1/organisations/" + id, admin)).get("channels")).isEmpty();
    }

    @Test
    void ordinaryUsersCannotChangeOrganisations() throws Exception {
        Session user = api().registerTenant("Not An Admin Co");
        api().post("/api/v1/admin/organisations", user, org("Sneaky Co")).andExpect(status().isForbidden());
    }

    @Test
    void websiteMustBeAnHttpUrl() throws Exception {
        Session admin = api().platformAdmin(jdbc);
        Map<String, Object> bad = org("Bad URL Co " + UUID.randomUUID());
        bad.put("website", "javascript:alert(1)");
        api().post("/api/v1/admin/organisations", admin, bad).andExpect(status().isBadRequest());
    }
}
