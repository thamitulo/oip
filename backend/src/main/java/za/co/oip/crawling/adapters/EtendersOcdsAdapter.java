package za.co.oip.crawling.adapters;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.adapter.AdapterResult;
import za.co.oip.crawling.adapter.AdapterResult.ItemError;
import za.co.oip.crawling.adapter.CrawlContext;
import za.co.oip.crawling.adapter.NormalisedOpportunity;
import za.co.oip.crawling.adapter.NormalisedOpportunity.DocumentLink;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.crawling.adapter.ProcurementSourceAdapter;
import za.co.oip.crawling.adapter.SourceDescriptor;
import za.co.oip.crawling.http.FetchException;
import za.co.oip.crawling.http.FetchResult;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;
import za.co.oip.opportunities.domain.OpportunityEnums.ProcurementCategory;
import za.co.oip.sources.domain.SourceEnums.AdapterType;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the National Treasury eTenders OCDS releases API
 * ({@code GET {sourceUrl}?PageNumber=&PageSize=&dateFrom=&dateTo=}).
 *
 * <p>Field mapping, based on observed data (2026-11-11):
 * <ul>
 *   <li>{@code tender.title} holds the bid/reference number; {@code tender.description} holds the
 *       human-readable title. The title shown is the description, abbreviated.</li>
 *   <li>{@code tender.tenderPeriod.endDate} is published with a {@code Z} suffix, but the observed times
 *       (mostly 10:00, 11:00, 12:00, 16:00) are South African business hours. By default the wall-clock
 *       time is therefore interpreted in {@code Africa/Johannesburg} ({@code "interpretTimesAsLocal": true}).
 *       The raw value is always kept in {@code closingRaw}. <b>This interpretation must be confirmed
 *       against published tender documents.</b></li>
 *   <li>A {@code value.amount} of 0 means "not published" and is not stored.</li>
 *   <li>{@code tender.status} is empty in practice; status is then inferred from the closing date and
 *       flagged as inferred.</li>
 *   <li>The API exposes no per-tender web page. The evidence URL is the exact API query that returned the
 *       release (it varies with paging); the source URL shown to users is the official tender document on
 *       etenders.gov.za, which is stable, falling back to the evidence URL when no document is published.</li>
 * </ul>
 */
@Component
public class EtendersOcdsAdapter implements ProcurementSourceAdapter {

    private final JsonMapper jsonMapper;

    public EtendersOcdsAdapter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public AdapterType type() {
        return AdapterType.ETENDERS_OCDS;
    }

    @Override
    public AdapterResult crawl(SourceDescriptor source, CrawlContext context) {
        int pageSize = source.configInt("pageSize", 100);
        int lookbackDays = source.configInt("lookbackDays", 3);
        int maxPages = source.configInt("maxPages", 50);
        boolean localTimes = !"false".equals(source.configText("interpretTimesAsLocal", "true"));
        ZoneId zone = ZoneId.of(source.configText("timeZone", "Africa/Johannesburg"));

        LocalDate today = LocalDate.now(context.clock().withZone(zone));
        LocalDate from = today.minusDays(lookbackDays);
        AdapterResult.Collector out = new AdapterResult.Collector();
        Integer lastStatus = null;
        String firstHash = null;

        for (int page = 1; page <= maxPages; page++) {
            URI uri = URI.create(source.sourceUrl() + "?PageNumber=" + page + "&PageSize=" + pageSize
                    + "&dateFrom=" + from + "&dateTo=" + today);
            FetchResult result;
            try {
                result = context.fetch(uri, source);
            } catch (FetchException e) {
                if (page == 1) {
                    return AdapterResult.failure(e.outcome(), e.getMessage(), e.httpStatus());
                }
                // Earlier pages were read; report the failure without discarding them.
                out.errors.add(new ItemError("PAGINATION_FAILED", e.getMessage(), e.url(), null));
                break;
            }
            lastStatus = result.status();
            firstHash = firstHash == null ? result.sha256() : firstHash;

            JsonNode root;
            try {
                root = jsonMapper.readTree(result.body());
            } catch (JacksonException e) {
                return AdapterResult.failure(CrawlOutcome.PARSER_FAILED, "Response is not valid JSON", result.status());
            }
            JsonNode releases = root.get("releases");
            if (releases == null || !releases.isArray()) {
                return AdapterResult.failure(CrawlOutcome.PARSER_FAILED,
                        "Response has no 'releases' array; the API format may have changed", result.status());
            }
            context.log("Page " + page + ": " + releases.size() + " releases");
            for (JsonNode release : releases) {
                try {
                    out.items.add(map(release, uri.toString(), today, localTimes, zone));
                } catch (RuntimeException e) {
                    out.errors.add(new ItemError("RELEASE_MAPPING_FAILED", e.getMessage(), uri.toString(),
                            text(release, "ocid")));
                }
            }
            boolean hasNext = root.has("links") && root.get("links").has("next");
            if (releases.isEmpty() || !hasNext) {
                break;
            }
            if (page == maxPages) {
                out.errors.add(new ItemError("TRUNCATED", "Stopped after maxPages=" + maxPages
                        + "; more releases are available", uri.toString(), null));
            }
        }
        return AdapterResult.success(out.items, out.errors, lastStatus, firstHash);
    }

    NormalisedOpportunity map(JsonNode release, String evidenceUrl, LocalDate today, boolean localTimes, ZoneId zone) {
        JsonNode tender = release.path("tender");
        String ocid = text(release, "ocid");
        if (ocid == null) {
            throw new IllegalArgumentException("Release has no ocid");
        }
        String bidNumber = ParsingSupport.clean(text(tender, "title"));
        String description = ParsingSupport.clean(text(tender, "description"));
        String title = description != null ? ParsingSupport.abbreviate(description, 300) : bidNumber;
        if (title == null) {
            throw new IllegalArgumentException("Release " + ocid + " has neither title nor description");
        }

        NormalisedOpportunity.Builder b = NormalisedOpportunity.builder()
                .sourceReference(ocid)
                .referenceNumber(bidNumber)
                .title(title)
                .description(description)
                .eventType(ParsingSupport.classifyProcurementMethod(text(tender, "procurementMethodDetails"), EventType.TENDER))
                .evidenceUrl(evidenceUrl);

        String mainCategory = text(tender, "mainProcurementCategory");
        if ((mainCategory == null || mainCategory.isBlank()) && tender.path("additionalProcurementCategories").size() > 0) {
            mainCategory = tender.path("additionalProcurementCategories").get(0).asString();
        }
        b.procurementCategory(category(mainCategory));
        b.sourceCategoryLabel(ParsingSupport.clean(mainCategory));

        JsonNode entity = tender.has("procuringEntity") ? tender.get("procuringEntity") : release.path("buyer");
        b.organisation(ParsingSupport.clean(text(entity, "name")), ParsingSupport.clean(text(entity, "id")));

        String endRaw = text(tender.path("tenderPeriod"), "endDate");
        LocalDateTime closingLocal = wallClock(endRaw, localTimes, zone);
        if (closingLocal != null) {
            boolean hasTime = !closingLocal.toLocalTime().equals(LocalTime.MIDNIGHT);
            b.closing(hasTime ? closingLocal.atZone(zone).toInstant() : null, closingLocal.toLocalDate(), hasTime, endRaw);
        }
        LocalDateTime startLocal = wallClock(text(tender.path("tenderPeriod"), "startDate"), localTimes, zone);
        if (startLocal != null) {
            b.publishedAt(startLocal.toLocalDate().atStartOfDay(zone).toInstant());
        }

        OpportunityStatus status = switch (String.valueOf(text(tender, "status")).toLowerCase()) {
            case "active" -> OpportunityStatus.OPEN;
            case "complete" -> OpportunityStatus.CLOSED;
            case "cancelled", "unsuccessful", "withdrawn" -> OpportunityStatus.CANCELLED;
            default -> null;
        };
        if (status != null) {
            b.status(status, false);
        } else if (closingLocal != null) {
            b.status(closingLocal.toLocalDate().isBefore(today) ? OpportunityStatus.EXPIRED : OpportunityStatus.OPEN, true);
        }

        JsonNode value = tender.path("value");
        if (value.has("amount") && value.get("amount").isNumber() && value.get("amount").decimalValue().signum() > 0) {
            b.estimatedValue(value.get("amount").decimalValue(), ParsingSupport.clean(text(value, "currency")));
        }

        List<DocumentLink> documents = new ArrayList<>();
        for (JsonNode d : tender.path("documents")) {
            String url = ParsingSupport.clean(text(d, "url"));
            if (url != null) {
                documents.add(new DocumentLink(ParsingSupport.clean(text(d, "title")), url,
                        ParsingSupport.clean(text(d, "format")), instant(text(d, "datePublished"))));
            }
        }
        b.documents(documents);
        // The API has no per-tender page, and the query URL depends on paging (it changes between runs). The
        // stable, user-openable original is the official document on etenders.gov.za, preferring the invitation.
        b.sourceUrl(documents.stream()
                .filter(d -> d.title() != null && d.title().toLowerCase().matches(
                        "(?s).*(invitation|advert|request for (bid|quotation|proposal|information)|\brfq\b|\brfp\b|bid document|tender document).*"))
                .findFirst().or(() -> documents.stream().findFirst())
                .map(DocumentLink::url).orElse(evidenceUrl));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("ocid", ocid);
        putIfPresent(attributes, "tenderId", text(tender, "id"));
        putIfPresent(attributes, "procurementMethod", text(tender, "procurementMethod"));
        putIfPresent(attributes, "procurementMethodDetails", text(tender, "procurementMethodDetails"));
        putIfPresent(attributes, "releaseDate", text(release, "date"));
        putIfPresent(attributes, "tenderPeriod.endDate", endRaw);
        b.attributes(attributes);
        return b.build();
    }

    private static LocalDateTime wallClock(String raw, boolean localTimes, ZoneId zone) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            OffsetDateTime odt = OffsetDateTime.parse(raw);
            return localTimes ? odt.toLocalDateTime() : odt.atZoneSameInstant(zone).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Unparseable date '" + raw + "'");
        }
    }

    private static Instant instant(String raw) {
        try {
            return raw == null ? null : OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ProcurementCategory category(String raw) {
        if (raw == null) {
            return ProcurementCategory.NOT_SPECIFIED;
        }
        return switch (raw) {
            case "goods" -> ProcurementCategory.GOODS;
            case "works" -> ProcurementCategory.WORKS;
            case "services" -> ProcurementCategory.SERVICES;
            case "consultingServices" -> ProcurementCategory.CONSULTING_SERVICES;
            default -> ProcurementCategory.NOT_SPECIFIED;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
