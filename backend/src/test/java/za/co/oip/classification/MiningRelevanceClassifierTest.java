package za.co.oip.classification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MiningRelevanceClassifierTest {

    final MiningRelevanceClassifier classifier = new MiningRelevanceClassifier();

    @Test
    void detectsMiningSubjectsWithEvidence() {
        assertThat(classifier.classify("Rehabilitation of derelict and ownerless mines in Limpopo", null, "Department X"))
                .hasValueSatisfying(e -> assertThat(e).contains("mines"));
        assertThat(classifier.classify("Supply of reagents", "for the tailings facility", null)).isPresent();
        assertThat(classifier.classify("Laboratory equipment", null, "MINTEK")).hasValue("Procuring entity: MINTEK");
    }

    @Test
    void avoidsCommonFalsePositives() {
        assertThat(classifier.classify("Determine the scope of examination services", null, "Health Department")).isEmpty();
        assertThat(classifier.classify("Supply of mineral water to offices", null, null)).isEmpty();
        assertThat(classifier.classify("Catering services", "Coal-fired boiler maintenance at hospital", null)).isEmpty();
        assertThat(classifier.classify("Feasibility study on the beneficiation of marula fruit", null, null)).isEmpty();
        assertThat(classifier.classify("Study on mineral beneficiation", null, null)).isPresent();
    }
}
