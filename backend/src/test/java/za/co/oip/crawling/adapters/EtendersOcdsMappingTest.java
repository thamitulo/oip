package za.co.oip.crawling.adapters;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.crawling.adapter.NormalisedOpportunity;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;
import za.co.oip.opportunities.domain.OpportunityEnums.ProcurementCategory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Mapping of the OCDS release structure observed from the eTenders API. Data below is synthetic test data. */
class EtendersOcdsMappingTest {

    static final String RELEASE = """
            {"ocid":"ocds-test-000001","id":"ocds-test-000001-2026/11/10","date":"2026-11-10T00:00:00Z",
             "tender":{"id":"000001","title":"TEST/BID/001/2026","status":"",
               "mainProcurementCategory":"","additionalProcurementCategories":["consultingServices"],
               "description":"TEST DATA - Appointment of a service provider for systems integration",
               "value":{"amount":0,"currency":"ZAR"},
               "documents":[{"id":"d1","title":"Invitation to Tender - TEST.pdf","url":"https://www.etenders.gov.za/home/Download?blobName=test.pdf","datePublished":"2026-11-09T14:39:10Z","format":"pdf"}],
               "tenderPeriod":{"startDate":"2026-11-10T00:00:00Z","endDate":"2026-12-08T11:00:00Z"},
               "procuringEntity":{"id":"91","name":"TEST Procuring Entity (SOC) Ltd"},
               "procurementMethod":"open","procurementMethodDetails":"Request for Proposal"}}
            """;

    final EtendersOcdsAdapter adapter = new EtendersOcdsAdapter(JsonMapper.builder().build());

    @Test
    void mapsObservedFieldSemantics() throws Exception {
        JsonNode release = JsonMapper.builder().build().readTree(RELEASE);
        NormalisedOpportunity o = adapter.map(release, "https://api.test/page1", LocalDate.of(2026, 11, 11), true,
                ZoneId.of("Africa/Johannesburg"));

        assertThat(o.sourceReference()).isEqualTo("ocds-test-000001");
        // tender.title carries the bid number; the description is the readable title
        assertThat(o.referenceNumber()).isEqualTo("TEST/BID/001/2026");
        assertThat(o.title()).startsWith("TEST DATA - Appointment");
        assertThat(o.eventType()).isEqualTo(EventType.RFP);
        assertThat(o.procurementCategory()).isEqualTo(ProcurementCategory.CONSULTING_SERVICES);
        assertThat(o.organisationName()).isEqualTo("TEST Procuring Entity (SOC) Ltd");
        assertThat(o.organisationExternalId()).isEqualTo("91");
        // 11:00 is interpreted as South African wall-clock time (09:00 UTC), and the raw value is kept
        assertThat(o.closingAt()).isEqualTo(Instant.parse("2026-12-08T09:00:00Z"));
        assertThat(o.closingDate()).isEqualTo(LocalDate.of(2026, 12, 8));
        assertThat(o.closingTimeSpecified()).isTrue();
        assertThat(o.closingRaw()).isEqualTo("2026-12-08T11:00:00Z");
        // amount 0 means "not published": never stored as a value
        assertThat(o.estimatedValue()).isNull();
        // empty status is inferred from the closing date and flagged as inferred
        assertThat(o.status()).isEqualTo(OpportunityStatus.OPEN);
        assertThat(o.statusInferred()).isTrue();
        assertThat(o.documents()).hasSize(1);
        assertThat(o.sourceUrl()).isEqualTo("https://www.etenders.gov.za/home/Download?blobName=test.pdf");
        assertThat(o.evidenceUrl()).isEqualTo("https://api.test/page1");
    }

    @Test
    void canTreatTimesAsTrueUtcWhenConfigured() throws Exception {
        JsonNode release = JsonMapper.builder().build().readTree(RELEASE);
        NormalisedOpportunity o = adapter.map(release, "u", LocalDate.of(2026, 11, 11), false, ZoneId.of("Africa/Johannesburg"));
        assertThat(o.closingAt()).isEqualTo(Instant.parse("2026-12-08T11:00:00Z"));
    }
}
