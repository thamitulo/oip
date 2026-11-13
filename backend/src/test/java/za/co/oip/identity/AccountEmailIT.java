package za.co.oip.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import za.co.oip.support.IntegrationTest;
import za.co.oip.support.TestApi.Session;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Invitations, password reset and e-mail verification (e-mail delivery is disabled in tests). */
class AccountEmailIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void invitedMemberSetsTheirOwnPasswordAndCannotSignInBefore() throws Exception {
        Session owner = api().registerTenant("Invite Co");
        String email = "invitee-" + System.nanoTime() + "@example.test";
        JsonNode added = api().read(api().post("/api/v1/users", owner,
                Map.of("email", email, "fullName", "New Person", "role", "STANDARD_USER")).andExpect(status().isCreated()));
        assertThat(added.get("member").get("invitationPending").asBoolean()).isTrue();
        assertThat(added.get("invitationEmailed").asBoolean()).isFalse();
        String url = added.get("invitationUrl").asString();
        assertThat(url).contains("/accept-invite?token=");
        String token = url.substring(url.indexOf("token=") + 6);
        // Only the hash is stored.
        assertThat(jdbc.queryForObject("select count(*) from account_tokens where token_hash = ?", Integer.class, token)).isZero();

        // Not usable before acceptance.
        api().post("/api/v1/auth/login", null, Map.of("email", email, "password", "anything-at-all-1"))
                .andExpect(status().isUnauthorized());

        api().post("/api/v1/auth/token-info", null, Map.of("token", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("INVITATION"))
                .andExpect(jsonPath("$.tenantName").value("Invite Co"))
                .andExpect(jsonPath("$.fullName").value("New Person"));
        api().post("/api/v1/auth/set-password", null, Map.of("token", token, "password", "my-own-password-1"))
                .andExpect(status().isNoContent());
        Session invitee = api().login(email, "my-own-password-1");
        assertThat(invitee.tenantId()).isEqualTo(owner.tenantId());
        api().get("/api/v1/users/me", invitee).andExpect(jsonPath("$.emailVerified").value(true));

        // Single use.
        api().post("/api/v1/auth/set-password", null, Map.of("token", token, "password", "another-password-2"))
                .andExpect(status().isUnprocessableContent());
        api().post("/api/v1/users/" + invitee.userId() + "/resend-invitation", owner, null)
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void forgotPasswordNeverRevealsWhetherAnAccountExists() throws Exception {
        Session owner = api().registerTenant("Reset Co");
        api().post("/api/v1/auth/forgot-password", null, Map.of("email", owner.email())).andExpect(status().isAccepted());
        api().post("/api/v1/auth/forgot-password", null, Map.of("email", "nobody-" + System.nanoTime() + "@example.test"))
                .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("select count(*) from account_tokens where user_id = ? and purpose = 'PASSWORD_RESET'",
                Integer.class, owner.userId())).isEqualTo(1);
        api().post("/api/v1/auth/set-password", null, Map.of("token", "not-a-real-token", "password", "whatever-password-1"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void registrationSendsAVerificationLinkAndUsersSeeTheirStatus() throws Exception {
        Session owner = api().registerTenant("Verify Co");
        assertThat(jdbc.queryForObject("select count(*) from account_tokens where user_id = ? and purpose = 'EMAIL_VERIFICATION'",
                Integer.class, owner.userId())).isEqualTo(1);
        api().get("/api/v1/users/me", owner).andExpect(jsonPath("$.emailVerified").value(false));
        api().post("/api/v1/auth/verify-email/resend", owner, null).andExpect(status().isAccepted());
        api().post("/api/v1/auth/verify-email", null, Map.of("token", "bogus")).andExpect(status().isUnprocessableContent());
    }
}
