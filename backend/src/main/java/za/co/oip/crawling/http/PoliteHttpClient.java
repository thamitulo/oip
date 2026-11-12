package za.co.oip.crawling.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.CrawlerProperties;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only way crawler code fetches remote content. It:
 * <ul>
 *   <li>identifies itself with a truthful User-Agent (no browser impersonation);</li>
 *   <li>honours robots.txt;</li>
 *   <li>rate-limits per host;</li>
 *   <li>bounds response size and request time;</li>
 *   <li>refuses loopback/private/link-local targets (SSRF protection), re-checking every redirect.</li>
 * </ul>
 * It deliberately has no support for solving CAPTCHAs, rotating identities or evading bot protection.
 */
@Component
public class PoliteHttpClient {

    private static final Logger log = LoggerFactory.getLogger(PoliteHttpClient.class);
    private static final int MAX_REDIRECTS = 5;
    private static final Duration ROBOTS_CACHE_TTL = Duration.ofHours(12);

    private final CrawlerProperties properties;
    private final Clock clock;
    private final HttpClient client;
    private final Map<String, Instant> nextAllowedRequest = new ConcurrentHashMap<>();
    private final Map<String, CachedRobots> robotsCache = new ConcurrentHashMap<>();

    /** With the JDK's default TLS trust only. */
    public PoliteHttpClient(CrawlerProperties properties, Clock clock) {
        this(properties, clock, defaultSslContext());
    }

    /** With reviewed per-host intermediate certificates (see {@link CrawlerTrustProperties}). */
    @org.springframework.beans.factory.annotation.Autowired
    public PoliteHttpClient(CrawlerProperties properties, Clock clock, CrawlerTrustProperties trust,
                            org.springframework.core.io.ResourceLoader resources) {
        this(properties, clock, sslContext(trust, resources));
    }

    public PoliteHttpClient(CrawlerProperties properties, Clock clock, javax.net.ssl.SSLContext sslContext) {
        this.properties = properties;
        this.clock = clock;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(sslContext)
                .build();
    }

    private static javax.net.ssl.SSLContext defaultSslContext() {
        try {
            return javax.net.ssl.SSLContext.getDefault();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds the TLS context; fails start-up if a configured certificate is missing or its fingerprint differs. */
    public static javax.net.ssl.SSLContext sslContext(CrawlerTrustProperties trust,
                                                      org.springframework.core.io.ResourceLoader resources) {
        try {
            java.util.List<HostScopedTrustManager.LoadedIntermediate> loaded = new java.util.ArrayList<>();
            for (CrawlerTrustProperties.Intermediate i : trust.additionalIntermediates()) {
                try (InputStream in = resources.getResource(i.certificate()).getInputStream()) {
                    var cert = HostScopedTrustManager.load(in, i.sha256());
                    loaded.add(new HostScopedTrustManager.LoadedIntermediate(java.util.Set.copyOf(i.hosts()), cert));
                    log.info("Crawler TLS: using intermediate '{}' for hosts {}", cert.getSubjectX500Principal().getName(), i.hosts());
                }
            }
            return HostScopedTrustManager.sslContext(loaded);
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Invalid crawler TLS trust configuration: " + e.getMessage(), e);
        }
    }

    /** Fetches a URL after checking robots.txt for it. */
    public FetchResult get(URI uri, int requestsPerMinute) throws FetchException {
        if (!isAllowedByRobots(uri, requestsPerMinute)) {
            throw new FetchException(CrawlOutcome.BLOCKED_BY_ROBOTS, "robots.txt disallows " + uri.getPath(),
                    uri.toString(), (Integer) null);
        }
        return fetch(uri, requestsPerMinute, "*/*");
    }

    /** Returns whether robots.txt on the URL's host permits this crawler to fetch it. */
    public boolean isAllowedByRobots(URI uri, int requestsPerMinute) throws FetchException {
        String origin = origin(uri);
        CachedRobots cached = robotsCache.get(origin);
        if (cached == null || cached.fetchedAt.plus(ROBOTS_CACHE_TTL).isBefore(clock.instant())) {
            cached = new CachedRobots(loadRobots(origin, requestsPerMinute), clock.instant());
            robotsCache.put(origin, cached);
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        return cached.robots.isAllowed(properties.robotsToken(), path);
    }

    private RobotsTxt loadRobots(String origin, int requestsPerMinute) throws FetchException {
        URI robotsUri = URI.create(origin + "/robots.txt");
        try {
            FetchResult result = fetch(robotsUri, requestsPerMinute, "text/plain");
            // Sites that answer /robots.txt with an HTML page (soft 404) have no robots policy.
            if (!result.isContentType("text/plain")) {
                return RobotsTxt.ALLOW_ALL;
            }
            return RobotsTxt.parse(result.text());
        } catch (FetchException e) {
            Integer status = e.httpStatus();
            if (status != null && status >= 400 && status < 500) {
                return RobotsTxt.ALLOW_ALL; // RFC 9309: unavailable (4xx) robots.txt means no restrictions
            }
            throw new FetchException(CrawlOutcome.SOURCE_UNAVAILABLE,
                    "Could not determine robots.txt policy: " + e.getMessage(), robotsUri.toString(), e);
        }
    }

    private FetchResult fetch(URI initial, int requestsPerMinute, String accept) throws FetchException {
        URI uri = initial;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            validateTarget(uri);
            throttle(uri.getHost().toLowerCase(Locale.ROOT), requestsPerMinute);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.requestTimeout())
                    .header("User-Agent", properties.userAgent())
                    .header("Accept", accept)
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                throw new FetchException(CrawlOutcome.SOURCE_UNAVAILABLE, "Request timed out", uri.toString(), e);
            } catch (IOException e) {
                throw new FetchException(CrawlOutcome.SOURCE_UNAVAILABLE, "Connection failed: " + e.getMessage(),
                        uri.toString(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException(CrawlOutcome.CRAWL_FAILED, "Interrupted", uri.toString(), e);
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400 && response.headers().firstValue("Location").isPresent()) {
                closeQuietly(response.body());
                uri = uri.resolve(response.headers().firstValue("Location").get());
                continue;
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                throw new FetchException(classify(status), "HTTP " + status, uri.toString(), status);
            }
            byte[] body = readLimited(response.body(), uri);
            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            return new FetchResult(uri, status, contentType, body, clock.instant(), sha256(body));
        }
        throw new FetchException(CrawlOutcome.CRAWL_FAILED, "Too many redirects", initial.toString(), (Integer) null);
    }

    static CrawlOutcome classify(int status) {
        return switch (status) {
            case 401, 407 -> CrawlOutcome.AUTHENTICATION_REQUIRED;
            case 403 -> CrawlOutcome.ACCESS_DENIED;
            case 429 -> CrawlOutcome.RATE_LIMITED;
            case 404, 410 -> CrawlOutcome.SOURCE_UNAVAILABLE;
            default -> status >= 500 ? CrawlOutcome.SOURCE_UNAVAILABLE : CrawlOutcome.CRAWL_FAILED;
        };
    }

    private void validateTarget(URI uri) throws FetchException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new FetchException(CrawlOutcome.NOT_PERMITTED, "Only http(s) URLs may be fetched", uri.toString(),
                    (Integer) null);
        }
        if (uri.getHost() == null) {
            throw new FetchException(CrawlOutcome.NOT_PERMITTED, "URL has no host", uri.toString(), (Integer) null);
        }
        if (properties.allowPrivateNetworks()) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress() || address.isMulticastAddress()
                        || isUniqueLocalIpv6(address)) {
                    throw new FetchException(CrawlOutcome.NOT_PERMITTED,
                            "Refusing to fetch a private or local network address", uri.toString(), (Integer) null);
                }
            }
        } catch (UnknownHostException e) {
            throw new FetchException(CrawlOutcome.SOURCE_UNAVAILABLE, "Unknown host " + uri.getHost(), uri.toString(), e);
        }
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private void throttle(String host, int requestsPerMinute) throws FetchException {
        long intervalMillis = 60_000L / Math.max(1, requestsPerMinute);
        Instant now = clock.instant();
        Instant slot = nextAllowedRequest.compute(host, (h, next) -> {
            Instant start = (next == null || next.isBefore(now)) ? now : next;
            return start.plusMillis(intervalMillis);
        }).minusMillis(intervalMillis);
        long waitMillis = Duration.between(now, slot).toMillis();
        if (waitMillis > 0) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException(CrawlOutcome.CRAWL_FAILED, "Interrupted while rate limiting", host, e);
            }
        }
    }

    private byte[] readLimited(InputStream in, URI uri) throws FetchException {
        try (in) {
            byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, properties.maxResponseBytes() + 1));
            if (data.length > properties.maxResponseBytes()) {
                throw new FetchException(CrawlOutcome.CRAWL_FAILED,
                        "Response exceeds " + properties.maxResponseBytes() + " bytes", uri.toString(), (Integer) null);
            }
            return data;
        } catch (IOException e) {
            throw new FetchException(CrawlOutcome.SOURCE_UNAVAILABLE, "Failed reading response", uri.toString(), e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            log.debug("Ignoring close failure", e);
        }
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (port > 0 ? ":" + port : "");
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record CachedRobots(RobotsTxt robots, Instant fetchedAt) {
    }
}
