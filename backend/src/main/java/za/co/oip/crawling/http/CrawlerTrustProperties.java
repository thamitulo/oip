package za.co.oip.crawling.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Public intermediate CA certificates that specific sites fail to send in their TLS handshake. Browsers fetch such
 * a missing intermediate automatically; the crawler instead uses a reviewed copy, only for the listed hosts.
 * Validation is not relaxed: the chain must still reach a root in the JDK trust store, and the hostname,
 * validity dates and key usage are still checked.
 *
 * @param additionalIntermediates per-host intermediates (empty by default)
 */
@ConfigurationProperties(prefix = "oip.crawler.trust")
public record CrawlerTrustProperties(List<Intermediate> additionalIntermediates) {

    public CrawlerTrustProperties {
        additionalIntermediates = additionalIntermediates == null ? List.of() : List.copyOf(additionalIntermediates);
    }

    /**
     * @param hosts       exact host names this intermediate may be used for
     * @param certificate PEM resource location, e.g. {@code classpath:crawler-trust/x.pem}
     * @param sha256      expected SHA-256 fingerprint of the certificate (hex, colons optional); checked at start-up
     * @param reason      why it is needed (for reviewers)
     */
    public record Intermediate(List<String> hosts, String certificate, String sha256, String reason) {
    }
}
