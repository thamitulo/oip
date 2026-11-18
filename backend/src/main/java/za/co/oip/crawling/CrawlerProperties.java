package za.co.oip.crawling;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Crawler configuration ({@code oip.crawler.*}).
 *
 * @param userAgent            identifies the crawler to source operators; should include a contact URL
 * @param schedulerEnabled     run scheduled crawls in this instance
 * @param maxConcurrentCrawls  sources crawled in parallel by this instance
 * @param requestTimeout       per-request timeout
 * @param maxResponseBytes     responses larger than this are rejected
 * @param leaseDuration        how long a crawl lease is held before another instance may take over
 * @param allowPrivateNetworks allow fetching loopback/private addresses (tests and local fixtures only)
 * @param snapshotExcerptChars characters of fetched content retained as evidence on each observation
 */
@ConfigurationProperties(prefix = "oip.crawler")
public record CrawlerProperties(String userAgent,
                                boolean schedulerEnabled,
                                int maxConcurrentCrawls,
                                Duration requestTimeout,
                                long maxResponseBytes,
                                Duration leaseDuration,
                                boolean allowPrivateNetworks,
                                int snapshotExcerptChars) {

    public CrawlerProperties {
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = "OIP-Crawler/0.1";
        }
        if (maxConcurrentCrawls <= 0) {
            maxConcurrentCrawls = 2;
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(30);
        }
        if (maxResponseBytes <= 0) {
            maxResponseBytes = 15L * 1024 * 1024;
        }
        if (leaseDuration == null) {
            leaseDuration = Duration.ofMinutes(30);
        }
        if (snapshotExcerptChars <= 0) {
            snapshotExcerptChars = 20_000;
        }
    }

    /** Contact hosts from the example configuration files, which tell source operators nothing. */
    private static final java.util.List<String> PLACEHOLDER_CONTACTS =
            java.util.List.of("example.invalid", "example.com", "example.org", "example.co.za", "your-domain", "localhost");

    /**
     * Whether the user agent lacks a real contact: no URL or e-mail at all, or one still pointing at a placeholder
     * from {@code .env.example} / {@code .env.production.example}.
     */
    public boolean lacksRealContact() {
        String ua = userAgent.toLowerCase(java.util.Locale.ROOT);
        boolean hasContact = ua.contains("http://") || ua.contains("https://") || ua.contains("@");
        return !hasContact || PLACEHOLDER_CONTACTS.stream().anyMatch(ua::contains);
    }

    /** The product token matched against robots.txt user-agent lines. */
    public String robotsToken() {
        String token = userAgent.split("[/ ]")[0];
        return token.isBlank() ? "OIP-Crawler" : token;
    }
}
