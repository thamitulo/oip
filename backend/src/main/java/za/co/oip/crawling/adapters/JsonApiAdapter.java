package za.co.oip.crawling.adapters;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.adapter.AdapterResult;
import za.co.oip.crawling.adapter.CrawlContext;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.crawling.adapter.ProcurementSourceAdapter;
import za.co.oip.crawling.adapter.SourceDescriptor;
import za.co.oip.crawling.http.FetchException;
import za.co.oip.crawling.http.FetchResult;
import za.co.oip.sources.domain.SourceEnums.AdapterType;

import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a public JSON endpoint. Configuration uses JSON Pointers (RFC 6901):
 * <pre>{@code
 * { "itemsPointer": "/data/items",
 *   "fields": { "referenceNumber": "/ref", "title": "/name", "closingDate": "/closes", "detailUrl": "/url" },
 *   "dateFormats": ["yyyy-MM-dd'T'HH:mm"] }
 * }</pre>
 * A response without the items array fails with PARSER_FAILED.
 */
@Component
public class JsonApiAdapter implements ProcurementSourceAdapter {

    private final JsonMapper jsonMapper;

    public JsonApiAdapter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public AdapterType type() {
        return AdapterType.JSON_API;
    }

    @Override
    public AdapterResult crawl(SourceDescriptor source, CrawlContext context) {
        JsonNode cfg = source.config();
        if (cfg == null || !cfg.has("itemsPointer") || !cfg.has("fields")) {
            return AdapterResult.failure(CrawlOutcome.PARSER_FAILED, "Parser configuration requires itemsPointer and fields", null);
        }
        FetchResult result;
        try {
            result = context.fetch(URI.create(source.configText("listUrl", source.sourceUrl())), source);
        } catch (FetchException e) {
            return AdapterResult.failure(e.outcome(), e.getMessage(), e.httpStatus());
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(result.body());
        } catch (JacksonException e) {
            return AdapterResult.failure(CrawlOutcome.PARSER_FAILED, "Response is not valid JSON", result.status());
        }
        JsonNode items = root.at(cfg.get("itemsPointer").asString());
        if (!items.isArray()) {
            return AdapterResult.failure(CrawlOutcome.PARSER_FAILED,
                    "No array at " + cfg.get("itemsPointer").asString(), result.status());
        }
        GenericFieldMapper mapper = new GenericFieldMapper(source);
        LocalDate today = LocalDate.now(context.clock().withZone(ParsingSupport.SOUTH_AFRICA));
        AdapterResult.Collector out = new AdapterResult.Collector();
        for (JsonNode item : items) {
            Map<String, String> raw = new LinkedHashMap<>();
            cfg.get("fields").properties().forEach(e -> {
                JsonNode v = item.at(e.getValue().asString());
                if (!v.isMissingNode() && !v.isNull()) {
                    raw.put(e.getKey(), v.isValueNode() ? v.asString() : v.toString());
                }
            });
            GenericFieldMapper.Result mapped = mapper.map(raw, result.uri(), today);
            if (mapped.opportunity() != null) {
                out.items.add(mapped.opportunity());
            } else {
                if (!mapped.skipped()) out.errors.add(mapped.error());
            }
        }
        return AdapterResult.success(out.items, out.errors, result.status(), result.sha256());
    }
}
