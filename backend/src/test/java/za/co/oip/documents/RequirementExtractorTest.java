package za.co.oip.documents;

import org.junit.jupiter.api.Test;
import za.co.oip.documents.RequirementExtractor.Type;
import za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementExtractorTest {

    final RequirementExtractor extractor = new RequirementExtractor();

    static final String TEXT = """
            INVITATION TO BID: TEST DATA ONLY.
            Bidders must have a CIDB grading of 6CE or higher. Bidders must be registered on the Central Supplier Database (CSD).
            A valid tax compliance status PIN is mandatory. The 80/20 preference point system will apply.
            Engineers must be registered with ECSA. The service provider must hold ISO 9001 certification.
            A compulsory site briefing will be held on 20 November 2026.
            Bids must be deposited in the tender box at 1 Main Street.
            Closing date: 4 December 2026 at 11:00.
            """;

    @Test
    void extractsRequirementsWithEvidence() {
        var result = extractor.extract(TEXT);
        assertThat(result.requirements()).extracting(RequirementExtractor.Finding::type).contains(
                Type.CIDB_GRADING, Type.CSD_REGISTRATION, Type.TAX_COMPLIANCE, Type.PREFERENCE_POINT_SYSTEM,
                Type.PROFESSIONAL_REGISTRATION, Type.CERTIFICATION, Type.COMPULSORY_BRIEFING);
        var cidb = result.requirements().stream().filter(f -> f.type() == Type.CIDB_GRADING).findFirst().orElseThrow();
        assertThat(cidb.value()).isEqualTo("6CE");
        assertThat(cidb.mandatory()).isTrue();
        assertThat(cidb.evidence()).contains("CIDB grading of 6CE");
        assertThat(result.requirements()).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(Type.PREFERENCE_POINT_SYSTEM);
            assertThat(f.value()).isEqualTo("80/20");
        });
    }

    @Test
    void extractsSubmissionAndClosing() {
        var result = extractor.extract(TEXT);
        assertThat(result.submission()).hasValueSatisfying(s -> assertThat(s.method()).isEqualTo(SubmissionMethod.PHYSICAL));
        assertThat(result.closing()).hasValueSatisfying(c -> {
            assertThat(c.date()).isEqualTo(LocalDate.of(2026, 12, 4));
            assertThat(c.dateTime()).isEqualTo(LocalDateTime.of(2026, 12, 4, 11, 0));
        });
        assertThat(extractor.extract("Proposals must be submitted electronically via the e-tender portal.").submission())
                .hasValueSatisfying(s -> assertThat(s.method()).isEqualTo(SubmissionMethod.ELECTRONIC));
    }

    @Test
    void nonCompulsoryBriefingIsNotCompulsory() {
        for (String text : new String[]{"A Non-Compulsory Briefing Session will be held on 3 March.",
                "There will be a non compulsory briefing.", "Attendance is not compulsory briefing-wise.",
                "A noncompulsory site briefing is scheduled."}) {
            assertThat(extractor.extract(text).requirements()).as(text)
                    .noneMatch(f -> f.type() == Type.COMPULSORY_BRIEFING);
        }
        assertThat(extractor.extract("A Compulsory Briefing Session will be held.").requirements())
                .anyMatch(f -> f.type() == Type.COMPULSORY_BRIEFING);
    }

    @Test
    void bothPreferenceSystemsListedIsRecordedOnceAsConditional() {
        var result = extractor.extract("""
                The 80/20 preference point system applies to bids up to R50 million.
                The 90/10 preference point system applies to bids above R50 million.""");
        assertThat(result.requirements()).filteredOn(f -> f.type() == Type.PREFERENCE_POINT_SYSTEM)
                .singleElement().satisfies(f -> assertThat(f.value()).startsWith("80/20 or 90/10"));
    }

    @Test
    void findsNothingInUnrelatedText() {
        var result = extractor.extract("The annual report describes our strategy and results.");
        assertThat(result.requirements()).isEmpty();
        assertThat(result.submission()).isEmpty();
        assertThat(result.closing()).isEmpty();
    }
}
