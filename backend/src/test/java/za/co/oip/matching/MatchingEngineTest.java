package za.co.oip.matching;

import org.junit.jupiter.api.Test;
import za.co.oip.matching.MatchingEngine.Capability;
import za.co.oip.matching.MatchingEngine.Label;
import za.co.oip.matching.MatchingEngine.Opportunity;
import za.co.oip.matching.MatchingEngine.Profile;
import za.co.oip.matching.MatchingEngine.Registration;
import za.co.oip.matching.MatchingEngine.Requirement;
import za.co.oip.matching.MatchingEngine.Weights;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingEngineTest {

    final MatchingEngine engine = new MatchingEngine();

    final Profile itCompany = new Profile(
            List.of("Software development", "Systems integration", "Cybersecurity"), List.of(),
            List.of("Information technology"), List.of(), List.of("GAUTENG"),
            List.of(new Capability("Java and Angular development", "Enterprise web applications and workflow systems")),
            List.of("ISO 27001"), List.of(new Registration("CSD", "National Treasury CSD", "MAAA000")), null, 1);

    @Test
    void relevantOpportunityScoresHighWithReasons() {
        var opp = new Opportunity("Supply and implementation of a procurement workflow system",
                "Software development and systems integration of an enterprise workflow system", "Services: Professional",
                "GAUTENG", false, "GOVERNMENT", 20L, false,
                List.of(new Requirement("CSD_REGISTRATION", "Central Supplier Database", true, "must be registered on CSD")));
        var r = engine.score(itCompany, opp, Weights.DEFAULT);
        assertThat(r.score()).isGreaterThanOrEqualTo(70);
        assertThat(r.label()).isEqualTo(Label.STRONG_MATCH);
        assertThat(r.matched()).anyMatch(m -> m.contains("Software development"));
        assertThat(r.disqualifiers()).isEmpty();
    }

    @Test
    void unrelatedOpportunityIsWeakEvenWithNeutralUnknowns() {
        var opp = new Opportunity("Cleaning and hygiene services for clinics", "Daily cleaning of 12 clinics", "Services: General",
                null, false, "GOVERNMENT", 20L, false, List.of());
        var r = engine.score(itCompany, opp, Weights.DEFAULT);
        assertThat(r.score()).isLessThan(45);
        assertThat(r.label()).isEqualTo(Label.WEAK_MATCH);
        assertThat(r.missing()).anyMatch(m -> m.contains("Location not published"));
    }

    @Test
    void missingMandatoryRequirementIsADisqualifierNotHiddenByScore() {
        var opp = new Opportunity("Software development for a construction management system",
                "Software development and systems integration", null, "GAUTENG", false, "GOVERNMENT", 20L, false,
                List.of(new Requirement("CIDB_GRADING", "6CE", true, "CIDB grading of 6CE is mandatory")));
        var r = engine.score(itCompany, opp, Weights.DEFAULT);
        assertThat(r.disqualifiers()).singleElement().asString().contains("CIDB grading 6CE");
        assertThat(r.label()).isEqualTo(Label.REQUIRES_MANUAL_REVIEW);
    }

    @Test
    void cidbGradeComparisonUsesLevelAndClass() {
        Profile graded = new Profile(List.of("Civil works"), List.of(), List.of(), List.of(), List.of("NATIONAL"), List.of(),
                List.of(), List.of(), "7CE", null);
        assertThat(MatchingEngine.satisfies(graded, new Requirement("CIDB_GRADING", "6CE", true, ""))).isTrue();
        assertThat(MatchingEngine.satisfies(graded, new Requirement("CIDB_GRADING", "8CE", true, ""))).isFalse();
        assertThat(MatchingEngine.satisfies(graded, new Requirement("CIDB_GRADING", "3GB", true, ""))).isNull();
    }

    @Test
    void emptyProfileNeverLooksLikeAMatch() {
        Profile empty = new Profile(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
        var r = engine.score(empty, new Opportunity("Anything", null, null, null, false, "GOVERNMENT", 5L, false, List.of()), Weights.DEFAULT);
        assertThat(r.score()).isZero();
        assertThat(r.missing().getFirst()).contains("Complete your company profile");
    }
}
