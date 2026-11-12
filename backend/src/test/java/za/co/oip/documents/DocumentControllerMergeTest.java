package za.co.oip.documents;

import org.junit.jupiter.api.Test;
import za.co.oip.documents.DocumentController.RequirementDto;
import za.co.oip.opportunities.domain.OpportunityEnums.Provenance;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentControllerMergeTest {

    static RequirementDto req(String type, String value) {
        return new RequirementDto(UUID.randomUUID(), type, value, null, Provenance.DOCUMENT, "evidence", "doc");
    }

    @Test
    void differentPreferenceSystemsAcrossDocumentsBecomeOneConditionalEntry() {
        var csd = req("CSD_REGISTRATION", "Central Supplier Database");
        var out = DocumentController.mergePreferenceSystems(List.of(csd, req("PREFERENCE_POINT_SYSTEM", "80/20"),
                req("PREFERENCE_POINT_SYSTEM", "90/10")));
        assertThat(out).hasSize(2).contains(csd);
        assertThat(out).filteredOn(r -> r.type().equals("PREFERENCE_POINT_SYSTEM")).singleElement()
                .satisfies(r -> assertThat(r.value()).startsWith("80/20 or 90/10"));
    }

    @Test
    void singlePreferenceSystemIsUnchanged() {
        var list = List.of(req("PREFERENCE_POINT_SYSTEM", "80/20"));
        assertThat(DocumentController.mergePreferenceSystems(list)).isEqualTo(list);
    }
}
