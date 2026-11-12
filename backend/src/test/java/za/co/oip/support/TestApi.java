package za.co.oip.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Small fluent helper around MockMvc for API-level tests. */
public class TestApi {

    private final MockMvc mvc;
    private final JsonMapper json;

    public TestApi(MockMvc mvc, JsonMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    /** Registers a new tenant + owner with unique email and returns the session. */
    public Session registerTenant(String companyName) throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@example.test";
        JsonNode body = read(perform(MockMvcRequestBuilders.post("/api/v1/auth/register"), null, Map.of(
                "email", email, "password", "correct-horse-battery", "fullName", "Test Owner",
                "companyName", companyName, "acceptTerms", true))
                .andExpect(status().isCreated()));
        return Session.from(body);
    }

    /** Registers a user, promotes them to platform administrator and signs in again to get the role in the token. */
    public Session platformAdmin(org.springframework.jdbc.core.JdbcTemplate jdbc) throws Exception {
        Session s = registerTenant("Platform Ops " + UUID.randomUUID());
        jdbc.update("update users set platform_admin = true where id = ?", s.userId());
        return login(s.email(), "correct-horse-battery");
    }

    public Session login(String email, String password) throws Exception {
        return Session.from(read(perform(MockMvcRequestBuilders.post("/api/v1/auth/login"), null,
                Map.of("email", email, "password", password)).andExpect(status().isOk())));
    }

    public ResultActions get(String url, Session s) throws Exception {
        return perform(MockMvcRequestBuilders.get(url), s, null);
    }

    public ResultActions post(String url, Session s, Object body) throws Exception {
        return perform(MockMvcRequestBuilders.post(url), s, body);
    }

    public ResultActions put(String url, Session s, Object body) throws Exception {
        return perform(MockMvcRequestBuilders.put(url), s, body);
    }

    public JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions perform(MockHttpServletRequestBuilder builder, Session s, Object body) throws Exception {
        if (s != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + s.accessToken());
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(builder);
    }

    public record Session(String accessToken, String refreshToken, UUID userId, UUID tenantId, String email) {
        static Session from(JsonNode body) {
            JsonNode user = body.get("user");
            return new Session(body.get("accessToken").asString(), body.get("refreshToken").asString(),
                    UUID.fromString(user.get("userId").asString()), UUID.fromString(user.get("tenantId").asString()),
                    user.get("email").asString());
        }
    }
}
