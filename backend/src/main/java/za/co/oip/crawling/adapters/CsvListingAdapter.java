package za.co.oip.crawling.adapters;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.adapter.AdapterResult;
import za.co.oip.crawling.adapter.CrawlContext;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.crawling.adapter.ProcurementSourceAdapter;
import za.co.oip.crawling.adapter.SourceDescriptor;
import za.co.oip.crawling.http.FetchException;
import za.co.oip.crawling.http.FetchResult;
import za.co.oip.sources.domain.SourceEnums.AdapterType;

import java.io.StringReader;
import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a CSV listing with a header row. Configuration maps platform fields to CSV column names:
 * <pre>{@code { "columns": { "title": "Description", "referenceNumber": "Bid No", "closingDate": "Closing" },
 *   "delimiter": ",", "dateFormats": ["yyyy/MM/dd"] } }</pre>
 * Excel listings are handled by the document pipeline (Phase 5) rather than here.
 */
@Component
public class CsvListingAdapter implements ProcurementSourceAdapter {

    @Override
    public AdapterType type() {
        return AdapterType.CSV_LISTING;
    }

    @Override
    public AdapterResult crawl(SourceDescriptor source, CrawlContext context) {
        JsonNode cfg = source.config();
        if (cfg == null || !cfg.has("columns")) {
            return AdapterResult.failure(CrawlOutcome.PARSER_FAILED, "Parser configuration requires columns", null);
        }
        FetchResult result;
        try {
            result = context.fetch(URI.create(source.sourceUrl()), source);
        } catch (FetchException e) {
            return AdapterResult.failure(e.outcome(), e.getMessage(), e.httpStatus());
        }
        String delimiter = source.configText("delimiter", ",");
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setHeader().setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true).setTrim(true).get();
        GenericFieldMapper mapper = new GenericFieldMapper(source);
        LocalDate today = LocalDate.now(context.clock().withZone(ParsingSupport.SOUTH_AFRICA));
        AdapterResult.Collector out = new AdapterResult.Collector();
        try (CSVParser parser = CSVParser.parse(new StringReader(result.text()), format)) {
            Map<String, Integer> header = parser.getHeaderMap();
            String titleColumn = cfg.get("columns").path("title").asString(null);
            if (titleColumn == null || header == null || !header.containsKey(titleColumn)) {
                return AdapterResult.failure(CrawlOutcome.PARSER_FAILED,
                        "Expected column '" + titleColumn + "' not found in CSV header", result.status());
            }
            for (CSVRecord record : parser) {
                Map<String, String> raw = new LinkedHashMap<>();
                cfg.get("columns").properties().forEach(e -> {
                    String column = e.getValue().asString();
                    if (record.isMapped(column) && record.isSet(column)) {
                        raw.put(e.getKey(), record.get(column));
                    }
                });
                GenericFieldMapper.Result mapped = mapper.map(raw, result.uri(), today);
                if (mapped.opportunity() != null) {
                    out.items.add(mapped.opportunity());
                } else {
                    if (!mapped.skipped()) out.errors.add(mapped.error());
                }
            }
        } catch (Exception e) {
            return AdapterResult.failure(CrawlOutcome.PARSER_FAILED, "Could not parse CSV: " + e.getMessage(), result.status());
        }
        return AdapterResult.success(out.items, out.errors, result.status(), result.sha256());
    }
}
