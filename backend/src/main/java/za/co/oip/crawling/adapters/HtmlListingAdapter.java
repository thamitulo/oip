package za.co.oip.crawling.adapters;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.adapter.AdapterResult;
import za.co.oip.crawling.adapter.CrawlContext;
import za.co.oip.crawling.adapter.ProcurementSourceAdapter;
import za.co.oip.crawling.adapter.SourceDescriptor;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.crawling.http.FetchException;
import za.co.oip.crawling.http.FetchResult;
import za.co.oip.sources.domain.SourceEnums.AdapterType;

import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads server-rendered HTML listings configured with CSS selectors. Example configuration:
 * <pre>{@code
 * { "listContainerSelector": "table.tenders",          // required: proves the page has the expected structure
 *   "itemSelector": "table.tenders tbody tr",
 *   "fields": { "title": {"selector": "td.title a"},
 *               "detailUrl": {"selector": "td.title a", "attr": "href"},
 *               "documentUrl": {"attr": "href"} },      // no selector = the item element itself
 *   "dateFormats": ["d MMM yyyy"], "defaultEventType": "RFQ",
 *   "nextPageSelector": "a.next", "maxPages": 5 }
 * }</pre>
 * If the list container is missing the run fails with {@link CrawlOutcome#PARSER_FAILED} instead of
 * reporting zero opportunities — an error page must never look like an empty listing.
 */
@Component
public class HtmlListingAdapter implements ProcurementSourceAdapter {

    @Override
    public AdapterType type() {
        return AdapterType.HTML_LISTING;
    }

    @Override
    public AdapterResult crawl(SourceDescriptor source, CrawlContext context) {
        JsonNode cfg = source.config();
        String containerSelector = source.configText("listContainerSelector", null);
        String itemSelector = source.configText("itemSelector", null);
        if (cfg == null || containerSelector == null || itemSelector == null || !cfg.has("fields")) {
            return AdapterResult.failure(CrawlOutcome.PARSER_FAILED,
                    "Parser configuration requires listContainerSelector, itemSelector and fields", null);
        }
        GenericFieldMapper mapper = new GenericFieldMapper(source);
        LocalDate today = LocalDate.now(context.clock().withZone(ParsingSupport.SOUTH_AFRICA));
        AdapterResult.Collector out = new AdapterResult.Collector();

        URI pageUri = URI.create(source.configText("listUrl", source.sourceUrl()));
        int maxPages = source.configInt("maxPages", 1);
        String nextSelector = source.configText("nextPageSelector", null);
        Integer lastStatus = null;
        String firstHash = null;

        for (int page = 1; page <= maxPages && pageUri != null; page++) {
            FetchResult result;
            try {
                result = context.fetch(pageUri, source);
            } catch (FetchException e) {
                if (page == 1) {
                    return AdapterResult.failure(e.outcome(), e.getMessage(), e.httpStatus());
                }
                out.errors.add(new AdapterResult.ItemError("PAGINATION_FAILED", e.getMessage(), e.url(), null));
                break;
            }
            lastStatus = result.status();
            firstHash = firstHash == null ? result.sha256() : firstHash;
            Document doc = Jsoup.parse(result.text(), result.uri().toString());
            if (doc.select(containerSelector).isEmpty()) {
                return AdapterResult.failure(CrawlOutcome.PARSER_FAILED,
                        "Expected listing structure '" + containerSelector + "' not found on " + result.uri()
                                + " (page " + page + "). The page may be an error page or its layout has changed.",
                        result.status());
            }
            Elements items = doc.select(itemSelector);
            context.log("Page " + page + ": " + items.size() + " items");
            for (Element item : items) {
                Map<String, String> raw = extract(item, cfg.get("fields"));
                GenericFieldMapper.Result mapped = mapper.map(raw, result.uri(), today);
                if (mapped.opportunity() != null) {
                    out.items.add(mapped.opportunity());
                } else {
                    if (!mapped.skipped()) out.errors.add(mapped.error());
                }
            }
            pageUri = null;
            if (nextSelector != null) {
                Element next = doc.selectFirst(nextSelector);
                String href = next == null ? null : next.absUrl("href");
                if (href != null && !href.isBlank()) {
                    pageUri = URI.create(href);
                }
            }
        }
        return AdapterResult.success(out.items, out.errors, lastStatus, firstHash);
    }

    static Map<String, String> extract(Element item, JsonNode fields) {
        Map<String, String> raw = new LinkedHashMap<>();
        fields.properties().forEach(e -> {
            JsonNode spec = e.getValue();
            String selector = spec.has("selector") ? spec.get("selector").asString() : null;
            String attr = spec.has("attr") ? spec.get("attr").asString() : null;
            Element target = selector == null || selector.isBlank() ? item : item.selectFirst(selector);
            if (target == null) {
                return;
            }
            String value = attr == null ? target.text() : target.attr(attr);
            if (spec.has("regex")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(spec.get("regex").asString()).matcher(value);
                if (!m.find()) {
                    value = null;
                } else if (m.groupCount() == 0) {
                    value = m.group();
                } else if (spec.has("joinGroups") && spec.get("joinGroups").asBoolean()) {
                    // e.g. "13th of July 2026" -> groups "13" and "July 2026" -> "13 July 2026"
                    StringBuilder joined = new StringBuilder();
                    for (int g = 1; g <= m.groupCount(); g++) {
                        if (m.group(g) != null) joined.append(joined.isEmpty() ? "" : " ").append(m.group(g));
                    }
                    value = joined.toString();
                } else {
                    value = m.group(1);
                }
            }
            if (value != null && !value.isBlank()) {
                raw.put(e.getKey(), value);
            }
        });
        return raw;
    }
}
