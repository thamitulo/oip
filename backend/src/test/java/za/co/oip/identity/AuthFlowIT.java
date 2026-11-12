package za.co.oip.identity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import za.co.oip.support.IntegrationTest;
import za.co.oip.support.TestApi.Session;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIT extends IntegrationTest {

    @Test
    void registerCreatesTenantOwnerAndAllowsLogin() throws Exception {
        Session session = api().registerTenant("Acme Engineering (Pty) Ltd");

        api().get("/api/v1/users/me", session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TENANT_OWNER"))
                .andExpect(jsonPath("$.tenantName").value("Acme Engineering (Pty) Ltd"))
                .andExpect(jsonPath("$.platformAdmin").value(false))
                .andExpect(jsonPath("$.permissions").isArray());

        api().get("/api/v1/tenants/current", session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Africa/Johannesburg"))
                .andExpect(jsonPath("$.slug").value(org.hamcrest.Matchers.startsWith("acme-engineering-pty-ltd-")));

        api().get("/api/v1/companies/profile", session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("Acme Engineering (Pty) Ltd"));

        Session again = api().login(session.email(), "correct-horse-battery");
        assertThat(again.tenantId()).isEqualTo(session.tenantId());
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameError() throws Exception {
        Session session = api().registerTenant("Login Test Co");

        JsonNode wrongPassword = api().read(api().post("/api/v1/auth/login", null,
                Map.of("email", session.email(), "password", "not-the-password"))
                .andExpect(status().isUnauthorized()));
        JsonNode unknownEmail = api().read(api().post("/api/v1/auth/login", null,
                Map.of("email", "nobody@example.test", "password", "not-the-password"))
                .andExpect(status().isUnauthorized()));

        assertThat(wrongPassword.get("detail")).isEqualTo(unknownEmail.get("detail"));
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        Session session = api().registerTenant("Dup Co");
        api().post("/api/v1/auth/register", null, Map.of("email", session.email().toUpperCase(),
                        "password", "correct-horse-battery", "fullName", "X", "companyName", "Other",
                        "acceptTerms", true))
                .andExpect(status().isConflict());
    }

    @Test
    void registrationValidatesPasswordAndTermsAcceptance() throws Exception {
        api().post("/api/v1/auth/register", null, Map.of("email", "weak@example.test", "password", "short",
                        "fullName", "X", "companyName", "Y", "acceptTerms", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(
                        org.hamcrest.Matchers.containsInAnyOrder("password", "acceptTerms")));
    }

    @Test
    void refreshTokensRotateAndReuseRevokesAllSessions() throws Exception {
        Session session = api().registerTenant("Refresh Co");

        JsonNode rotated = api().read(api().post("/api/v1/auth/refresh", null,
                Map.of("refreshToken", session.refreshToken())).andExpect(status().isOk()));
        String newRefresh = rotated.get("refreshToken").asString();
        assertThat(newRefresh).isNotEqualTo(session.refreshToken());

        // Re-using the old token is treated as theft: it fails and the new token is revoked too.
        api().post("/api/v1/auth/refresh", null, Map.of("refreshToken", session.refreshToken()))
                .andExpect(status().isUnauthorized());
        api().post("/api/v1/auth/refresh", null, Map.of("refreshToken", newRefresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        Session session = api().registerTenant("Logout Co");
        api().post("/api/v1/auth/logout", null, Map.of("refreshToken", session.refreshToken()))
                .andExpect(status().isNoContent());
        api().post("/api/v1/auth/refresh", null, Map.of("refreshToken", session.refreshToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointsRequireAValidToken() throws Exception {
        api().get("/api/v1/users/me", null).andExpect(status().isUnauthorized());
        api().get("/api/v1/users/me", new Session("not-a-jwt", null, null, null, null))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointsRequirePlatformAdmin() throws Exception {
        Session owner = api().registerTenant("Not Admin Co");
        api().get("/api/v1/admin/sources", owner).andExpect(status().isForbidden());
    }
}
