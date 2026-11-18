package za.co.oip.crawling;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlerPropertiesTest {

    static CrawlerProperties withAgent(String userAgent) {
        return new CrawlerProperties(userAgent, true, 0, null, 0, null, false, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "OIP-Crawler/0.1 (+https://example.invalid/crawler)",   // application.yml / .env.example default
            "OIP-Crawler/1.0 (+https://your-domain/crawler)",       // .env.production.example default
            "OIP-Crawler/1.0 (+https://oip.example.co.za/crawler)", // the sample domain in the docs
            "OIP-Crawler/1.0",                                       // no contact at all
            ""})
    void placeholdersAndMissingContactsAreRejected(String userAgent) {
        assertThat(withAgent(userAgent).lacksRealContact()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "OIP-Crawler/1.0 (+https://tenders.acme.co.za/crawler)",
            "OIP-Crawler/1.0 (crawler@acme.co.za)"})
    void realContactsAreAccepted(String userAgent) {
        assertThat(withAgent(userAgent).lacksRealContact()).isFalse();
    }
}
