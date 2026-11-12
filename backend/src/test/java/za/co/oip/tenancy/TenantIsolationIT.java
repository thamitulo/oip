package za.co.oip.tenancy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import za.co.oip.companies.CompanyProfileIT;
import za.co.oip.support.IntegrationTest;
import za.co.oip.support.TestApi.Session;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Data belonging to one tenant must never be visible to or modifiable by another tenant. */
class TenantIsolationIT extends IntegrationTest {

    @Test
    void tenantsSeeOnlyTheirOwnMembersAndProfile() throws Exception {
        Session a = api().registerTenant("Tenant A");
        Session b = api().registerTenant("Tenant B");

        api().put("/api/v1/companies/profile", a, CompanyProfileIT.profile("Tenant A Legal Name"))
                .andExpect(status().isOk());

        api().get("/api/v1/companies/profile", b)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("Tenant B"))
                .andExpect(jsonPath("$.services", hasSize(0)));

        JsonNode bMembers = api().read(api().get("/api/v1/users", b).andExpect(status().isOk()));
        assertThat(bMembers).hasSize(1);
        assertThat(bMembers.get(0).get("id").asString()).isEqualTo(b.userId().toString());
    }

    @Test
    void tenantCannotModifyAnotherTenantsMember() throws Exception {
        Session a = api().registerTenant("Victim Co");
        Session b = api().registerTenant("Attacker Co");

        api().put("/api/v1/users/" + a.userId(), b, Map.of("status", "DISABLED"))
                .andExpect(status().isNotFound());

        // The victim can still use the platform.
        api().get("/api/v1/users/me", a).andExpect(status().isOk());
        api().login(a.email(), "correct-horse-battery");
    }

    @Test
    void lastOwnerCannotBeDemoted() throws Exception {
        Session owner = api().registerTenant("Solo Owner Co");
        api().put("/api/v1/users/" + owner.userId(), owner, Map.of("role", "STANDARD_USER"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void adminCannotGrantHigherRoleThanOwn() throws Exception {
        Session owner = api().registerTenant("Hierarchy Co");
        String email = "admin-" + System.nanoTime() + "@example.test";
        api().post("/api/v1/users", owner, Map.of("email", email, "fullName", "Admin", "role", "TENANT_ADMIN",
                "initialPassword", "admin-password-1")).andExpect(status().isCreated());
        Session admin = api().login(email, "admin-password-1");

        api().post("/api/v1/users", admin, Map.of("email", "x" + System.nanoTime() + "@example.test",
                        "fullName", "Usurper", "role", "TENANT_OWNER", "initialPassword", "usurper-password"))
                .andExpect(status().isForbidden());
        api().put("/api/v1/users/" + owner.userId(), admin, Map.of("status", "DISABLED"))
                .andExpect(status().isForbidden());
    }
}
