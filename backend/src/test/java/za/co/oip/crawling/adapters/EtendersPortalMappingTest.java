package za.co.oip.crawling.adapters;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.crawling.adapter.NormalisedOpportunity;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;
import za.co.oip.opportunities.domain.OpportunityEnums.ProcurementCategory;
import za.co.oip.opportunities.domain.OpportunityEnums.Provenance;
import za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Field mapping of the eTenders portal feed, based on the observed record shape. Values are synthetic test data. */
class EtendersPortalMappingTest {

    final JsonMapper json = JsonMapper.builder().build();
    final EtendersPortalAdapter adapter = new EtendersPortalAdapter(json);

    String record(boolean eSubmission, String delivery, String conditions) {
        return """
                {"id": 900001, "tender_No": "TEST 01/2027", "description": "TEST DATA - Rehabilitation of a derelict and ownerless mine shaft",
                 "category": "Services: Professional", "type": "Request for Proposal",
                 "department": "TEST Municipality", "status": "Published",
                 "closing_Date": "2027-01-29T10:30:00", "date_Published": "2026-11-11T00:00:00",
                 "compulsory_briefing_session": "2026-11-20T11:00:00", "briefingVenue": "TEST Community Hall",
                 "briefingSession": true, "briefingCompulsory": true, "province": "Gauteng", "town": "JOHANNESBURG",
                 "contactPerson": "SUPPLY CHAIN", "email": "tenders@example.test", "telephone": "N/A",
                 "conditions": %s, "eSubmission": %s, "twoEnvelopeSubmission": false, "delivery": %s,
                 "supportDocument": [{"supportDocumentID": "b0932764-0000-0000-0000-000000000001",
                    "fileName": "TEST Invitation to Bid (Final).pdf", "extension": ".pdf", "active": true,
                    "dateModified": "2026-11-11T14:43:41.99"}]}
                """.formatted(conditions == null ? "null" : json.writeValueAsString(conditions), eSubmission,
                delivery == null ? "null" : json.writeValueAsString(delivery));
    }

    NormalisedOpportunity map(String record) {
        JsonNode node = json.readTree(record);
        return adapter.map(node, "ocds-9t57fa-", "https://portal.test/page", LocalDate.of(2026, 11, 11));
    }

    @Test
    void mapsCoreFieldsInSouthAfricanTime() {
        NormalisedOpportunity o = map(record(false, "33 HOOFD STREET -BRAAMFONTEIN -JOHANNESBURG-2017", "N/A"));
        assertThat(o.sourceReference()).isEqualTo("ocds-9t57fa-900001");
        assertThat(o.referenceNumber()).isEqualTo("TEST 01/2027");
        assertThat(o.eventType()).isEqualTo(EventType.RFP);
        assertThat(o.procurementCategory()).isEqualTo(ProcurementCategory.CONSULTING_SERVICES);
        assertThat(o.province()).isEqualTo("GAUTENG");
        assertThat(o.closingAt()).isEqualTo(Instant.parse("2027-01-29T08:30:00Z")); // 10:30 SAST
        assertThat(o.closingTimeSpecified()).isTrue();
        assertThat(o.status()).isEqualTo(OpportunityStatus.OPEN);
        assertThat(o.briefingCompulsory()).isTrue();
        assertThat(o.briefingAt()).isEqualTo(Instant.parse("2026-11-20T09:00:00Z"));
        assertThat(o.briefingVenue()).isEqualTo("TEST Community Hall");
        assertThat(o.contactPhone()).isNull(); // "N/A" is not a value
        assertThat(o.specialConditions()).isNull();
        assertThat(o.sourceUrl()).startsWith("https://www.etenders.gov.za/home/Download?blobName=b0932764-0000-0000-0000-000000000001.pdf")
                .contains("TEST%20Invitation%20to%20Bid%20%28Final%29.pdf");
    }

    @Test
    void physicalSubmissionWhenDeliveryAddressAndNoESubmission() {
        var s = map(record(false, "33 HOOFD STREET -BRAAMFONTEIN -JOHANNESBURG-2017", null)).submission();
        assertThat(s.method()).isEqualTo(SubmissionMethod.PHYSICAL);
        assertThat(s.address()).isEqualTo("33 HOOFD STREET, BRAAMFONTEIN, JOHANNESBURG, 2017");
        assertThat(s.provenance()).isEqualTo(Provenance.SOURCE);
    }

    @Test
    void electronicWhenFlagged() {
        var s = map(record(true, "Some office", null)).submission();
        assertThat(s.method()).isEqualTo(SubmissionMethod.ELECTRONIC);
        assertThat(s.channel()).isEqualTo("eTenders e-Submission");
        assertThat(s.provenance()).isEqualTo(Provenance.SOURCE);
    }

    @Test
    void electronicWhenConditionsSayElectronicSubmission() {
        var s = map(record(false, "Some office",
                "Tenders are to be submitted electronically via Eskom E- tendering site by the stated time")).submission();
        assertThat(s.method()).isEqualTo(SubmissionMethod.ELECTRONIC);
        assertThat(s.provenance()).isEqualTo(Provenance.DOCUMENT);
        assertThat(s.evidence()).contains("submitted electronically");
    }

    @Test
    void notSpecifiedWithoutAnyInformation() {
        assertThat(map(record(false, null, "TENDER FEE R608.89")).submission().method())
                .isEqualTo(SubmissionMethod.NOT_SPECIFIED);
    }
}
