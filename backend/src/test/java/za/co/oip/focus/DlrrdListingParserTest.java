package za.co.oip.focus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.CrawlerProperties;
import za.co.oip.crawling.adapter.AdapterResult;
import za.co.oip.crawling.adapter.CrawlContext;
import za.co.oip.crawling.adapter.NormalisedOpportunity;
import za.co.oip.crawling.adapter.SourceDescriptor;
import za.co.oip.crawling.adapters.HtmlListingAdapter;
import za.co.oip.crawling.http.PoliteHttpClient;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.sources.domain.SourceEnums.AdapterType;
import za.co.oip.support.FixtureServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeded DLRRD state-farm parser configuration, run against an excerpt of the real page markup
 * (src/test/resources/fixtures/dlrrd-state-farms-excerpt-2026-11-12.html) served locally.
 */
class DlrrdListingParserTest {

    static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");
    FixtureServer server;

    @BeforeEach
    void start() throws Exception {
        server = new FixtureServer().route("/robots.txt", 404, "text/plain", "not found")
                .html("/state-farms", Files.readString(Path.of("src/test/resources/fixtures/dlrrd-state-farms-excerpt-2026-11-12.html")));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** The latest parser configuration for the DLRRD source in the migrations. */
    static String latestConfig() throws Exception {
        String config = null;
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path p : files.sorted().toList()) {
                Matcher m = Pattern.compile("(?:'HTML_LISTING',\\s*|parser_config\\s*=\\s*)'(\\{.*?\\})'(?=\\s*(?:,\\s*'MEDIUM'|,|\\s*WHERE|\\s*\\n))",
                        Pattern.DOTALL).matcher(Files.readString(p));
                String sql = Files.readString(p);
                if (!sql.contains("c0000000-0000-0000-0000-000000000040")) continue;
                while (m.find()) config = m.group(1).replace("''", "'");
            }
        }
        assertThat(config).as("DLRRD parser configuration in migrations").isNotNull();
        return config;
    }

    AdapterResult crawl() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), SAST);
        var props = new CrawlerProperties("OIP-Crawler/test", false, 1, Duration.ofSeconds(10), 5_000_000,
                Duration.ofMinutes(5), true, 2000);
        var context = new CrawlContext(new PoliteHttpClient(props, clock), clock, 2000);
        var source = new SourceDescriptor(UUID.randomUUID(), "DLRRD fixture", server.url("/state-farms"),
                AdapterType.HTML_LISTING, 600, JsonMapper.builder().build().readTree(latestConfig()),
                "Department of Land Reform and Rural Development");
        return new HtmlListingAdapter().crawl(source, context);
    }

    static NormalisedOpportunity byDocument(List<NormalisedOpportunity> items, String file) {
        return items.stream().filter(o -> o.documents().stream().anyMatch(d -> d.url().endsWith(file))).findFirst()
                .orElseThrow(() -> new AssertionError("no item for " + file));
    }

    @Test
    void parsesStateFarmAdverts() throws Exception {
        AdapterResult result = crawl();
        assertThat(result.outcome()).isEqualTo(CrawlOutcome.SUCCESS);
        List<NormalisedOpportunity> items = result.opportunities();
        items.forEach(o -> System.out.println("  " + o.closingDate() + " " + o.closingAt() + " " + o.province() + " | "
                + o.title().substring(0, Math.min(70, o.title().length()))));
        result.itemErrors().forEach(e -> System.out.println("  ERR " + e));

        assertThat(items).allMatch(o -> o.eventType() == EventType.LAND_LEASE);
        // Forms, withdrawal notices and adverts without a closing date are not opportunities.
        assertThat(items).noneMatch(o -> o.title().toLowerCase().contains("withdrawal")
                || o.title().toLowerCase().contains("declaration of applicant"));

        var kliprivier = byDocument(items, "portion-25-47-kliprivier-73-jt.pdf");
        assertThat(kliprivier.closingAt()).isEqualTo(LocalDateTime.of(2026, 11, 18, 16, 0).atZone(SAST).toInstant());
        assertThat(kliprivier.documents().getFirst().url())
                .isEqualTo(server.url("/images/application_to_lease_state_farms/2026/portion-25-47-kliprivier-73-jt.pdf"));

        var laagerspoort = byDocument(items, "/laagerspoort.pdf");
        assertThat(laagerspoort.closingAt()).isEqualTo(LocalDateTime.of(2026, 11, 23, 16, 0).atZone(SAST).toInstant());
        assertThat(laagerspoort.province()).isEqualTo("GAUTENG");

        // "KwaZulu- Natal" and "Kwazulu-Natal" spellings.
        assertThat(byDocument(items, "brakvlei.pdf").province()).isEqualTo("KWAZULU_NATAL");
        assertThat(byDocument(items, "bellevue-16900ft-vers2.pdf").province()).isEqualTo("KWAZULU_NATAL");

        // Ordinal dates: "1st of August 2026", "13th of July 2026", "14th of May 2026".
        assertThat(byDocument(items, "doornfontein-plas-project.pdf").closingDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(byDocument(items, "kanonkrans-no-108-04-05.pdf").closingDate()).isEqualTo(LocalDate.of(2026, 7, 13));
        // "Closing19 August 2024" (no space) and "Closing on the 22 December 2023".
        assertThat(byDocument(items, "vetted-advert-for-allocation-of-vlakplaats-lease-unit-2.pdf").closingDate())
                .isEqualTo(LocalDate.of(2024, 8, 19));
        assertThat(byDocument(items, "vetted-standard-advert-for-allocation-of-solms-delta-farm.pdf").closingDate())
                .isEqualTo(LocalDate.of(2023, 12, 22));
        // Date only, no time published.
        var vlakplaats = byDocument(items, "2026/vlakplaats.pdf");
        assertThat(vlakplaats.closingDate()).isEqualTo(LocalDate.of(2026, 6, 19));
        assertThat(vlakplaats.closingTimeSpecified()).isFalse();
        // Site briefing details are kept.
        assertThat(byDocument(items, "vetted_advert_mara_farm-_08_september_25.pdf").briefingDetails()).contains("site briefing");

        // The nested 2023/2024 lists are read item by item, not as one wrapper item.
        assertThat(items.stream().filter(o -> o.title().contains("Solms Delta")).count()).isEqualTo(1);
        assertThat(items).noneMatch(o -> o.title().length() > 1000);

        // Open on 2026-11-12: Laagerspoort (two advert files), Kalkbank, Sterkspruit, Kliprivier.
        assertThat(items.stream().filter(o -> o.closingDate().isAfter(LocalDate.of(2026, 11, 11)))
                .map(o -> o.documents().getFirst().url().replaceAll(".*/", "")).distinct().sorted().toList())
                .containsExactly("Farm-Kalkbank-376-IT.pdf", "Farm-Sterkspruit-508-IS.pdf", "Laagerspoort-23Oct2026.pdf",
                        "laagerspoort.pdf", "portion-25-47-kliprivier-73-jt.pdf");
    }
}
