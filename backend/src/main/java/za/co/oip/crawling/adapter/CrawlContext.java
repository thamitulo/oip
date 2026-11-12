package za.co.oip.crawling.adapter;

import za.co.oip.crawling.http.FetchException;
import za.co.oip.crawling.http.FetchResult;
import za.co.oip.crawling.http.PoliteHttpClient;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-run services for an adapter: polite HTTP access, evidence capture, a clock and a run log.
 * Every successful fetch through {@link #fetch} is recorded as an observation automatically.
 */
public class CrawlContext {

    /** Evidence of one fetch. */
    public record Observation(String url, int httpStatus, String contentType, String contentHash, long bytes,
                              java.time.Instant fetchedAt, String excerpt) {
    }

    private final PoliteHttpClient http;
    private final Clock clock;
    private final int excerptChars;
    private final List<Observation> observations = new ArrayList<>();
    private final List<String> log = new ArrayList<>();

    public CrawlContext(PoliteHttpClient http, Clock clock, int excerptChars) {
        this.http = http;
        this.clock = clock;
        this.excerptChars = excerptChars;
    }

    public FetchResult fetch(URI uri, SourceDescriptor source) throws FetchException {
        FetchResult result = http.get(uri, source.rateLimitPerMinute());
        observe(result);
        log("GET " + uri + " -> " + result.status() + " (" + result.body().length + " bytes)");
        return result;
    }

    public void observe(FetchResult r) {
        String text = r.text();
        String excerpt = text.length() > excerptChars ? text.substring(0, excerptChars) : text;
        observations.add(new Observation(r.uri().toString(), r.status(), r.contentType(), r.sha256(),
                r.body().length, r.fetchedAt(), excerpt));
    }

    public void log(String line) {
        log.add(clock.instant() + " " + line);
    }

    public Clock clock() {
        return clock;
    }

    public PoliteHttpClient http() {
        return http;
    }

    public List<Observation> observations() {
        return Collections.unmodifiableList(observations);
    }

    public List<String> logLines() {
        return Collections.unmodifiableList(log);
    }
}
