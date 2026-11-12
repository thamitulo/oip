package za.co.oip.companies;

import org.junit.jupiter.api.Test;
import za.co.oip.support.IntegrationTest;
import za.co.oip.support.TestApi.Session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class CompanyProfileIT extends IntegrationTest {

    public static Map<String, Object> profile(String legalName) {
        Map<String, Object> p = new HashMap<>();
        p.put("legalName", legalName);
        p.put("tradingName", "Bluetank");
        p.put("province", "GAUTENG");
        p.put("city", "Johannesburg");
        p.put("bbbeeLevel", "LEVEL_1");
        p.put("enterpriseClassification", "QSE");
        p.put("blackOwnershipPercentage", 51);
        p.put("website", "https://example.test");
        p.put("industries", List.of("Information Technology", "Mining"));
        p.put("services", List.of("Software development", "software development", "Systems integration"));
        p.put("operatingRegions", List.of("Gauteng", "KZN", "NATIONAL"));
        p.put("certifications", List.of(Map.of("name", "ISO 27001", "verificationStatus", "VERIFIED")));
        p.put("registrations", List.of(Map.of("registrationType", "CSD", "registrationBody", "National Treasury CSD",
                "registrationNumber", "MAAA0000000")));
        p.put("capabilities", List.of(Map.of("name", "Java and Angular development", "yearsExperience", 8)));
        return p;
    }

    @Test
    void ownerCanUpdateProfileAndListsAreNormalised() throws Exception {
        Session s = api().registerTenant("Bluetank Example");

        api().put("/api/v1/companies/profile", s, profile("Bluetank Example (Pty) Ltd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value("Bluetank Example (Pty) Ltd"))
                .andExpect(jsonPath("$.services", hasSize(2)))
                .andExpect(jsonPath("$.operatingRegions", contains("GAUTENG", "KWAZULU_NATAL", "NATIONAL")))
                // Users cannot self-certify a claim as VERIFIED.
                .andExpect(jsonPath("$.certifications[0].verificationStatus").value("SELF_DECLARED"))
                .andExpect(jsonPath("$.registrations[0].verificationStatus").value("SELF_DECLARED"));

        // A second full update replaces collections rather than appending.
        Map<String, Object> p = profile("Bluetank Example (Pty) Ltd");
        p.put("services", List.of("Cybersecurity"));
        api().put("/api/v1/companies/profile", s, p)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services", contains("Cybersecurity")));

        api().get("/api/v1/companies/capabilities", s)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Java and Angular development"));
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        Session s = api().registerTenant("Validation Co");

        Map<String, Object> p = profile("");
        p.put("blackOwnershipPercentage", 150);
        api().put("/api/v1/companies/profile", s, p).andExpect(status().isBadRequest());

        Map<String, Object> badRegion = profile("Validation Co");
        badRegion.put("operatingRegions", List.of("Atlantis"));
        api().put("/api/v1/companies/profile", s, badRegion).andExpect(status().isUnprocessableContent());
    }

    @Test
    void readOnlyUserCannotEditProfile() throws Exception {
        Session owner = api().registerTenant("Roles Co");
        String email = "reader-" + System.nanoTime() + "@example.test";
        api().post("/api/v1/users", owner, Map.of("email", email, "fullName", "Reader",
                "role", "READ_ONLY_USER", "initialPassword", "reader-password-1")).andExpect(status().isCreated());
        Session reader = api().login(email, "reader-password-1");

        api().get("/api/v1/companies/profile", reader).andExpect(status().isOk());
        api().put("/api/v1/companies/profile", reader, profile("Hacked")).andExpect(status().isForbidden());
    }
}
