package za.co.oip.common.reference;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProvinceTest {

    @ParameterizedTest
    @CsvSource({
            "KwaZulu-Natal, KWAZULU_NATAL",
            "kwazulu natal, KWAZULU_NATAL",
            "KZN, KWAZULU_NATAL",
            "Gauteng, GAUTENG",
            "  north west , NORTH_WEST",
            "Western Cape, WESTERN_CAPE",
            "LIMPOPO, LIMPOPO"
    })
    void parsesCommonSpellings(String input, Province expected) {
        assertThat(Province.parse(input)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({"Atlantis", "''", "National"})
    void rejectsUnknownValues(String input) {
        assertThat(Province.parse(input)).isEmpty();
    }
}
