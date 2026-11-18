package za.co.oip.companies;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import za.co.oip.support.IntegrationTest;
import za.co.oip.support.TestApi.Session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Company details used on tender forms, and directors with encrypted identity numbers. All values are fake. */
public class CompanyFormDetailsIT extends IntegrationTest {

    public static final String FAKE_ID = "8001015009087";

    @Autowired
    JdbcTemplate jdbc;

    public static Map<String, Object> details(String name) {
        Map<String, Object> p = CompanyProfileIT.profile(name);
        p.put("registrationNumber", "2019/123456/07");
        p.put("vatNumber", "4123456789");
        p.put("csdSupplierNumber", "MAAA0001234");
        p.put("incomeTaxNumber", "9123456789");
        p.put("uifReferenceNumber", "123456789");
        p.put("compensationFundNumber", "990001234567");
        p.put("cidbCrsNumber", "10001234");
        p.put("physicalAddress", "1 Test Street, Sandton, 2196");
        p.put("postalAddress", "PO Box 1, Sandton, 2146");
        p.put("bbbeeCertificateNumber", "BEE-0001");
        p.put("bbbeeCertificateExpiry", "2027-06-30");
        p.put("bbbeeVerificationAgency", "Test Verification Agency");
        return p;
    }

    @Test
    void formDetailsAreSavedAndValidated() throws Exception {
        Session s = api().registerTenant("Form Details Co");
        JsonNode saved = api().read(api().put("/api/v1/companies/profile", s, details("Form Details Co")).andExpect(status().isOk()));
        assertThat(saved.get("csdSupplierNumber").asString()).isEqualTo("MAAA0001234");
        assertThat(saved.get("compensationFundNumber").asString()).isEqualTo("990001234567");
        assertThat(saved.get("bbbeeCertificateExpiry").asString()).isEqualTo("2027-06-30");

        for (var bad : List.of(Map.of("csdSupplierNumber", "MAAA12"), Map.of("vatNumber", "1234567890"),
                Map.of("registrationNumber", "123456"), Map.of("incomeTaxNumber", "12345"),
                Map.of("uifReferenceNumber", "<script>"))) {
            Map<String, Object> p = details("Form Details Co");
            p.putAll(bad);
            api().put("/api/v1/companies/profile", s, p).andExpect(status().isBadRequest());
        }
    }

    @Test
    void companyCanHaveSeveralDirectorsWithEncryptedMaskedIdNumbers() throws Exception {
        Session s = api().registerTenant("Directors Co");
        Map<String, Object> first = new HashMap<>(Map.of("fullName", "Thandi Example", "role", "DIRECTOR", "idType", "SA_ID",
                "idNumber", FAKE_ID, "shareholdingPercentage", 60, "inServiceOfState", false));
        JsonNode d1 = api().read(api().post("/api/v1/companies/profile/directors", s, first).andExpect(status().isCreated()));
        assertThat(d1.get("idNumberMasked").asString()).endsWith("9087").doesNotContain("800101");
        api().post("/api/v1/companies/profile/directors", s, Map.of("fullName", "Sipho Example", "role", "MEMBER",
                "idType", "PASSPORT", "idNumber", "A1234567", "shareholdingPercentage", 40)).andExpect(status().isCreated());

        JsonNode list = api().read(api().get("/api/v1/companies/profile/directors", s));
        assertThat(list).hasSize(2);
        assertThat(list.toString()).doesNotContain(FAKE_ID).doesNotContain("A1234567");

        // Stored encrypted: no plaintext in the table.
        String stored = jdbc.queryForObject("select id_number_enc from company_directors where id = ?::uuid", String.class,
                d1.get("id").asString());
        assertThat(stored).startsWith("v1:").doesNotContain(FAKE_ID);

        // Updating without an idNumber keeps it; an invalid SA ID is rejected.
        first.remove("idNumber");
        first.put("fullName", "Thandi Example-Renamed");
        JsonNode updated = api().read(api().put("/api/v1/companies/profile/directors/" + d1.get("id").asString(), s, first));
        assertThat(updated.get("idNumberMasked").asString()).endsWith("9087");
        first.put("idNumber", "8001015009088");
        api().put("/api/v1/companies/profile/directors/" + d1.get("id").asString(), s, first).andExpect(status().isUnprocessableContent());

        // Another tenant cannot see or touch them.
        Session other = api().registerTenant("Other Directors Co");
        assertThat(api().read(api().get("/api/v1/companies/profile/directors", other))).isEmpty();
        api().delete("/api/v1/companies/profile/directors/" + d1.get("id").asString(), other).andExpect(status().isNotFound());

        api().delete("/api/v1/companies/profile/directors/" + d1.get("id").asString(), s).andExpect(status().isNoContent());
        assertThat(api().read(api().get("/api/v1/companies/profile/directors", s))).hasSize(1);
    }

    @Test
    void readOnlyUsersCannotSeeDirectors() throws Exception {
        Session owner = api().registerTenant("Private Directors Co");
        String email = "reader-" + System.nanoTime() + "@example.test";
        api().post("/api/v1/users", owner, Map.of("email", email, "fullName", "Reader",
                "role", "READ_ONLY_USER", "initialPassword", "reader-password-1")).andExpect(status().isCreated());
        Session reader = api().login(email, "reader-password-1");
        api().get("/api/v1/companies/profile/directors", reader).andExpect(status().isForbidden());
    }

    @Test
    void saIdNumberCheck() {
        assertThat(SaIdNumber.isValid(FAKE_ID)).isTrue();
        assertThat(SaIdNumber.isValid("8001015009088")).isFalse();
        assertThat(SaIdNumber.isValid("8013015009087")).isFalse();
        assertThat(SaIdNumber.isValid("80010150090")).isFalse();
    }
}
