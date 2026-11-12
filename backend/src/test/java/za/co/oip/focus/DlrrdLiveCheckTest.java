package za.co.oip.focus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.CrawlerProperties;
import za.co.oip.crawling.adapter.CrawlContext;
import za.co.oip.crawling.adapter.SourceDescriptor;
import za.co.oip.crawling.adapters.HtmlListingAdapter;
import za.co.oip.crawling.http.PoliteHttpClient;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.sources.domain.SourceEnums.AdapterType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in check of the seeded DLRRD configuration against the real page (one polite request), using the reviewed
 * intermediate certificate the site omits from its TLS handshake.
 * Run with {@code OIP_LIVE_TESTS=true ./mvnw test -Dtest=DlrrdLiveCheckTest}.
 */
@EnabledIfEnvironmentVariable(named = "OIP_LIVE_TESTS", matches = "true")
class DlrrdLiveCheckTest {

    @Test
    void liveStateFarmListingParses() throws Exception {
        String config = DlrrdListingParserTest.latestConfig();
        JsonMapper json = JsonMapper.builder().build();
        var props = new CrawlerProperties("OIP-Crawler/0.1 (+https://example.invalid/crawler)", false, 1,
                Duration.ofSeconds(30), 15_000_000, Duration.ofMinutes(5), false, 2000);
        var context = new CrawlContext(new PoliteHttpClient(props, Clock.systemUTC(), PoliteHttpClient.sslContext(
                new za.co.oip.crawling.http.CrawlerTrustProperties(java.util.List.of(new za.co.oip.crawling.http.CrawlerTrustProperties.Intermediate(
                        java.util.List.of("www.dlrrd.gov.za", "dlrrd.gov.za"),
                        "classpath:crawler-trust/sectigo-public-server-authentication-ca-ov-r36.pem",
                        "6542D176BED50F193C0CE297AE44ECD8A0A86BEC2EDE682769344059B4E78530", "live check"))),
                new org.springframework.core.io.DefaultResourceLoader())), Clock.systemUTC(), 2000);
        var source = new SourceDescriptor(UUID.randomUUID(), "DLRRD live",
                "https://www.dlrrd.gov.za/index.php/component/content/article/235-application-to-lease-state-farms?catid=79&Itemid=437",
                AdapterType.HTML_LISTING, 6, json.readTree(config), "Department of Land Reform and Rural Development");
        var result = new HtmlListingAdapter().crawl(source, context);
        System.out.println("DLRRD live: outcome=" + result.outcome() + " items=" + result.opportunities().size()
                + " itemErrors=" + result.itemErrors().size() + " message=" + result.message() + " http=" + result.httpStatus());
        result.itemErrors().stream().limit(5).forEach(e -> System.out.println("  ERR " + e));
        result.opportunities().stream().filter(o -> o.closingDate() != null && o.closingDate().isAfter(java.time.LocalDate.now()))
                .forEach(o -> System.out.println("  OPEN " + o.closingDate() + " " + o.closingAt() + " " + o.province() + " | "
                        + o.title().substring(0, Math.min(90, o.title().length()))));
        assertThat(result.outcome()).isEqualTo(CrawlOutcome.SUCCESS);
        assertThat(result.opportunities()).hasSizeGreaterThan(100).allMatch(o -> o.eventType() == EventType.LAND_LEASE);
    }
}
