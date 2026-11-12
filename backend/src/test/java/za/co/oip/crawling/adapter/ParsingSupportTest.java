package za.co.oip.crawling.adapter;

import org.junit.jupiter.api.Test;
import za.co.oip.opportunities.domain.OpportunityEnums.DocumentType;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParsingSupportTest {

    @Test
    void parsesSeptAbbreviationUsedBySomeSources() {
        var parsed = ParsingSupport.parseLocal("24 Sept 2026", List.of("d MMM yyyy"));
        assertThat(parsed).isPresent();
        assertThat(parsed.get().date()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(parsed.get().hasTime()).isFalse();
    }

    @Test
    void parsesOrdinalsAndTimes() {
        var parsed = ParsingSupport.parseLocal("3rd March 2027 11:00", List.of("d MMMM yyyy HH:mm"));
        assertThat(parsed).isPresent();
        assertThat(parsed.get().dateTime()).isEqualTo(LocalDateTime.of(2027, 3, 3, 11, 0));
    }

    @Test
    void unparseableDateIsEmptyRatherThanGuessed() {
        assertThat(ParsingSupport.parseLocal("sometime soon", List.of("d MMM yyyy"))).isEmpty();
    }

    @Test
    void classifiesEventTypesByWholeWordKeyword() {
        Map<String, String> keywords = Map.of("EOI", "EOI", "RFQ", "RFQ");
        assertThat(ParsingSupport.classifyEventType("EOI Advert for Splicing", keywords, EventType.OTHER)).isEqualTo(EventType.EOI);
        assertThat(ParsingSupport.classifyEventType("GEOID survey", keywords, EventType.OTHER)).isEqualTo(EventType.OTHER);
        assertThat(ParsingSupport.classifyProcurementMethod("Request for Quotation", EventType.TENDER)).isEqualTo(EventType.RFQ);
        assertThat(ParsingSupport.classifyProcurementMethod("Request for Bid(Open-Tender)", EventType.OTHER)).isEqualTo(EventType.TENDER);
    }

    @Test
    void classifiesDocuments() {
        assertThat(ParsingSupport.classifyDocument("BOQ to be completed by bidders.pdf")).isEqualTo(DocumentType.BILL_OF_QUANTITIES);
        assertThat(ParsingSupport.classifyDocument("Addendum 1 - extension")).isEqualTo(DocumentType.ADDENDUM);
        assertThat(ParsingSupport.classifyDocument("List of Tender Returnables.pdf")).isEqualTo(DocumentType.RETURNABLE_SCHEDULE);
        assertThat(ParsingSupport.fileFormat("https://x.test/a/b.PDF?download=1")).isEqualTo("pdf");
        assertThat(ParsingSupport.fileFormat("https://x.test/download?id=1")).isNull();
    }
}
