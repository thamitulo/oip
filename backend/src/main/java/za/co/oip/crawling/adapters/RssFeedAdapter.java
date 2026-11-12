package za.co.oip.crawling.adapters;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.adapter.AdapterResult;
import za.co.oip.crawling.adapter.AdapterResult.ItemError;
import za.co.oip.crawling.adapter.CrawlContext;
import za.co.oip.crawling.adapter.NormalisedOpportunity;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.crawling.adapter.ProcurementSourceAdapter;
import za.co.oip.crawling.adapter.SourceDescriptor;
import za.co.oip.crawling.http.FetchException;
import za.co.oip.crawling.http.FetchResult;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.sources.domain.SourceEnums.AdapterType;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads RSS/Atom feeds. Feeds rarely carry closing dates, so none are inferred; the entry's publication
 * date is kept. Event type is classified from the entry title using {@code eventTypeKeywords}.
 */
@Component
public class RssFeedAdapter implements ProcurementSourceAdapter {

    @Override
    public AdapterType type() {
        return AdapterType.RSS_FEED;
    }

    @Override
    public AdapterResult crawl(SourceDescriptor source, CrawlContext context) {
        FetchResult result;
        try {
            result = context.fetch(URI.create(source.sourceUrl()), source);
        } catch (FetchException e) {
            return AdapterResult.failure(e.outcome(), e.getMessage(), e.httpStatus());
        }
        SyndFeed feed;
        try {
            SyndFeedInput input = new SyndFeedInput();
            input.setAllowDoctypes(false); // XXE protection
            feed = input.build(new InputSource(new ByteArrayInputStream(result.body())));
        } catch (Exception e) {
            return AdapterResult.failure(CrawlOutcome.PARSER_FAILED, "Not a valid RSS/Atom feed: " + e.getMessage(),
                    result.status());
        }
        Map<String, String> keywords = new LinkedHashMap<>();
        if (source.config() != null && source.config().has("eventTypeKeywords")) {
            source.config().get("eventTypeKeywords").properties().forEach(e -> keywords.put(e.getKey(), e.getValue().asString()));
        }
        EventType fallback = EventType.valueOf(source.configText("defaultEventType", "OTHER"));
        AdapterResult.Collector out = new AdapterResult.Collector();
        for (SyndEntry entry : feed.getEntries()) {
            String title = ParsingSupport.clean(entry.getTitle());
            String link = GenericFieldMapper.absolute(result.uri(), entry.getLink());
            String id = entry.getUri() != null && !entry.getUri().isBlank() ? entry.getUri() : link;
            if (title == null || id == null) {
                out.errors.add(new ItemError("MISSING_FIELDS", "Feed entry lacks a title or identifier", link, id));
                continue;
            }
            String description = entry.getDescription() == null ? null
                    : ParsingSupport.clean(org.jsoup.Jsoup.parse(entry.getDescription().getValue()).text());
            out.items.add(NormalisedOpportunity.builder()
                    .sourceReference(id)
                    .title(ParsingSupport.abbreviate(title, 1000))
                    .description(description)
                    .eventType(ParsingSupport.classifyEventType(title, keywords, fallback))
                    .organisation(source.organisationName(), null)
                    .publishedAt(entry.getPublishedDate() == null ? null : entry.getPublishedDate().toInstant())
                    .sourceUrl(link != null ? link : result.uri().toString())
                    .evidenceUrl(result.uri().toString())
                    .build());
        }
        return AdapterResult.success(out.items, out.errors, result.status(), result.sha256());
    }
}
