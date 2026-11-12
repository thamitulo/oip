package za.co.oip.crawling.adapters;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.common.reference.Province;
import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.adapter.AdapterResult;
import za.co.oip.crawling.adapter.AdapterResult.ItemError;
import za.co.oip.crawling.adapter.CrawlContext;
import za.co.oip.crawling.adapter.NormalisedOpportunity;
import za.co.oip.crawling.adapter.NormalisedOpportunity.DocumentLink;
import za.co.oip.crawling.adapter.NormalisedOpportunity.Submission;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.crawling.adapter.ProcurementSourceAdapter;
import za.co.oip.crawling.adapter.SourceDescriptor;
import za.co.oip.crawling.http.FetchException;
import za.co.oip.crawling.http.FetchResult;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;
import za.co.oip.opportunities.domain.OpportunityEnums.ProcurementCategory;
import za.co.oip.opportunities.domain.OpportunityEnums.Provenance;
import za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod;
import za.co.oip.sources.domain.SourceEnums.AdapterType;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the eTenders portal's public feed of published tenders
 * ({@code GET {sourceUrl}?draw=1&start=&length=&status=1}), the same data the portal's tender search shows.
 *
 * <p>Compared with the OCDS API it covers every currently published tender (not a date window) and carries the
 * e-submission flag, the physical delivery address, province, compulsory briefing date/venue, contact details
 * and special conditions. Times are published without a zone and are South African local time.
 *
 * <p>Submission method mapping (observed 2026-11-11):
 * <ul>
 *   <li>{@code eSubmission = true} → ELECTRONIC via eTenders e-Submission (provenance SOURCE).</li>
 *   <li>Otherwise, special conditions that explicitly require electronic or e-mail submission → ELECTRONIC
 *       (provenance DOCUMENT, the condition text kept as evidence).</li>
 *   <li>Otherwise, a published delivery address → PHYSICAL to that address (provenance SOURCE).</li>
 *   <li>Otherwise NOT_SPECIFIED.</li>
 * </ul>
 * Identifiers use the OCDS form {@code ocds-9t57fa-{id}}, so records continue those read from the OCDS API.
 */
@Component
public class EtendersPortalAdapter implements ProcurementSourceAdapter {

    private static final Pattern ELECTRONIC_CONDITION = Pattern.compile(
            "(?i)\\b(submitted|submit|submission)\\b[^.]{0,80}\\b(electronic(?:ally)?|e-?tender(?:ing)?|online|via (?:the )?portal|e-?mail(?:ed)?)\\b"
                    + "|\\b(electronic(?:ally)?|e-?mail(?:ed)?)\\b[^.]{0,40}\\bsubmi(?:t|ssion|tted)\\b");
    private static final String PORTAL_DOWNLOAD = "https://www.etenders.gov.za/home/Download?blobName=";

    private final JsonMapper jsonMapper;

    public EtendersPortalAdapter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public AdapterType type() {
        return AdapterType.ETENDERS_PORTAL;
    }

    @Override
    public AdapterResult crawl(SourceDescriptor source, CrawlContext context) {
        int pageSize = source.configInt("pageSize", 100);
        int maxPages = source.configInt("maxPages", 40);
        String prefix = source.configText("ocidPrefix", "ocds-9t57fa-");
        LocalDate today = LocalDate.now(context.clock().withZone(ParsingSupport.SOUTH_AFRICA));
        AdapterResult.Collector out = new AdapterResult.Collector();
        Integer lastStatus = null;
        String firstHash = null;
        long total = -1;

        for (int page = 0; page < maxPages; page++) {
            int start = page * pageSize;
            if (total >= 0 && start >= total) {
                break;
            }
            URI uri = URI.create(source.sourceUrl() + "?draw=" + (page + 1) + "&start=" + start + "&length=" + pageSize + "&status=1");
            FetchResult result;
            try {
                result = context.fetch(uri, source);
            } catch (FetchException e) {
                if (page == 0) {
                    return AdapterResult.failure(e.outcome(), e.getMessage(), e.httpStatus());
                }
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
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || !root.has("recordsTotal")) {
                return AdapterResult.failure(CrawlOutcome.PARSER_FAILED,
                        "Response lacks 'data'/'recordsTotal'; the portal format may have changed", result.status());
            }
            total = root.get("recordsTotal").asLong();
            context.log("Page " + (page + 1) + ": " + data.size() + " of " + total + " tenders");
            for (JsonNode t : data) {
                try {
                    out.items.add(map(t, prefix, uri.toString(), today));
                } catch (RuntimeException e) {
                    out.errors.add(new ItemError("RECORD_MAPPING_FAILED", e.getMessage(), uri.toString(), text(t, "tender_No")));
                }
            }
            if (data.isEmpty()) {
                break;
            }
            if (page == maxPages - 1 && (long) (page + 1) * pageSize < total) {
                out.errors.add(new ItemError("TRUNCATED", "Stopped after maxPages=" + maxPages + " of " + total + " tenders",
                        uri.toString(), null));
            }
        }
        return AdapterResult.success(out.items, out.errors, lastStatus, firstHash);
    }

    NormalisedOpportunity map(JsonNode t, String prefix, String evidenceUrl, LocalDate today) {
        String id = text(t, "id");
        if (id == null) {
            throw new IllegalArgumentException("Tender has no id");
        }
        String description = clean(text(t, "description"));
        String reference = clean(text(t, "tender_No"));
        String title = description != null ? ParsingSupport.abbreviate(description, 300) : reference;
        if (title == null) {
            throw new IllegalArgumentException("Tender " + id + " has neither description nor number");
        }
        String organisation = clean(firstNonBlank(text(t, "department"), text(t, "organ_of_State")));
        String category = clean(text(t, "category"));

        NormalisedOpportunity.Builder b = NormalisedOpportunity.builder()
                .sourceReference(prefix + id)
                .referenceNumber(reference)
                .title(title)
                .description(description)
                .eventType(ParsingSupport.classifyProcurementMethod(text(t, "type"), EventType.TENDER))
                .procurementCategory(category(category))
                .sourceCategoryLabel(category)
                .organisation(organisation, null)
                .locality(clean(text(t, "town")))
                .evidenceUrl(evidenceUrl);
        Province.parse(text(t, "province")).ifPresent(p -> b.province(p.name()));

        LocalDateTime closing = localDateTime(text(t, "closing_Date"));
        if (closing != null) {
            boolean hasTime = !closing.toLocalTime().equals(LocalTime.MIDNIGHT);
            b.closing(hasTime ? closing.atZone(ParsingSupport.SOUTH_AFRICA).toInstant() : null, closing.toLocalDate(), hasTime,
                    text(t, "closing_Date"));
        }
        LocalDateTime published = localDateTime(text(t, "date_Published"));
        if (published != null) {
            b.publishedAt(published.toLocalDate().atStartOfDay(ParsingSupport.SOUTH_AFRICA).toInstant());
        }
        String status = clean(text(t, "status"));
        if ("published".equalsIgnoreCase(status)) {
            boolean passed = closing != null && closing.toLocalDate().isBefore(today);
            b.status(passed ? OpportunityStatus.EXPIRED : OpportunityStatus.OPEN, passed);
        } else if (status != null) {
            b.status(ParsingSupport.mapStatus(status, Map.of()).orElse(OpportunityStatus.UNKNOWN), false);
        }

        // Briefing session
        if (t.path("briefingSession").asBoolean(false)) {
            LocalDateTime briefing = localDateTime(text(t, "compulsory_briefing_session"));
            Instant briefingAt = briefing == null ? null : briefing.atZone(ParsingSupport.SOUTH_AFRICA).toInstant();
            String venue = clean(text(t, "briefingVenue"));
            boolean compulsory = t.path("briefingCompulsory").asBoolean(false);
            StringBuilder details = new StringBuilder(compulsory ? "Compulsory briefing session" : "Briefing session (not compulsory)");
            if (briefing != null) details.append(" on ").append(briefing.toLocalDate()).append(' ').append(briefing.toLocalTime());
            if (venue != null) details.append(" at ").append(venue);
            b.briefing(details.toString(), compulsory);
            b.briefingSchedule(briefingAt, venue);
        }

        b.contact(clean(text(t, "contactPerson")), clean(text(t, "email")), clean(text(t, "telephone")));
        String conditions = clean(text(t, "conditions"));
        b.specialConditions(conditions);
        b.submission(submission(t, conditions));

        List<DocumentLink> documents = new ArrayList<>();
        for (JsonNode d : t.path("supportDocument")) {
            if (!d.path("active").asBoolean(true)) continue;
            String docId = text(d, "supportDocumentID");
            String fileName = text(d, "fileName");
            String ext = text(d, "extension");
            if (docId == null || ext == null) continue;
            String url = PORTAL_DOWNLOAD + docId + ext + (fileName == null ? ""
                    : "&downloadedFileName=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));
            documents.add(new DocumentLink(fileName, url, ext.replace(".", "").toLowerCase(Locale.ROOT),
                    instant(text(d, "dateModified"))));
        }
        b.documents(documents);
        b.sourceUrl(documents.stream()
                .filter(d -> d.title() != null && d.title().toLowerCase(Locale.ROOT).matches(
                        "(?s).*(invitation|advert|request for (bid|quotation|proposal|information)|\\brfq\\b|\\brfp\\b|bid document|tender document).*"))
                .findFirst().or(() -> documents.stream().findFirst())
                .map(DocumentLink::url).orElse(evidenceUrl));

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("tenderId", id);
        put(attributes, "type", text(t, "type"));
        put(attributes, "eSubmission", text(t, "eSubmission"));
        put(attributes, "delivery", text(t, "delivery"));
        put(attributes, "closing_Date", text(t, "closing_Date"));
        b.attributes(attributes);
        return b.build();
    }

    static Submission submission(JsonNode t, String conditions) {
        Boolean twoEnvelope = t.has("twoEnvelopeSubmission") ? t.get("twoEnvelopeSubmission").asBoolean(false) : null;
        if (t.path("eSubmission").asBoolean(false)) {
            return new Submission(SubmissionMethod.ELECTRONIC, "eTenders e-Submission", null, twoEnvelope, Provenance.SOURCE,
                    "eTenders flags this tender for electronic submission (eSubmission = true).");
        }
        if (conditions != null && ELECTRONIC_CONDITION.matcher(conditions).find()) {
            return new Submission(SubmissionMethod.ELECTRONIC, "As stated in the tender conditions", null, twoEnvelope,
                    Provenance.DOCUMENT, "Special conditions: " + ParsingSupport.abbreviate(conditions, 400));
        }
        String delivery = clean(text(t, "delivery"));
        if (delivery != null && delivery.replaceAll("[-\\s]", "").length() > 3) {
            return new Submission(SubmissionMethod.PHYSICAL, "Hand delivery / tender box", delivery.replaceAll("\\s*-\\s*", ", "),
                    twoEnvelope, Provenance.SOURCE,
                    "eTenders lists a delivery address and does not flag the tender for electronic submission.");
        }
        return Submission.NOT_SPECIFIED;
    }

    static ProcurementCategory category(String label) {
        if (label == null) return ProcurementCategory.NOT_SPECIFIED;
        String l = label.toLowerCase(Locale.ROOT);
        if (l.startsWith("services: professional")) return ProcurementCategory.CONSULTING_SERVICES;
        if (l.startsWith("services")) return ProcurementCategory.SERVICES;
        if (l.startsWith("supplies") || l.startsWith("goods")) return ProcurementCategory.GOODS;
        if (l.startsWith("construction") || l.contains("works")) return ProcurementCategory.WORKS;
        if (l.startsWith("disposal")) return ProcurementCategory.DISPOSAL;
        return ProcurementCategory.NOT_SPECIFIED;
    }

    private static LocalDateTime localDateTime(String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("0001-")) return null;
        try {
            return LocalDateTime.parse(raw.length() > 19 ? raw.substring(0, 19) : raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Unparseable date '" + raw + "'");
        }
    }

    private static Instant instant(String raw) {
        try {
            LocalDateTime dt = localDateTime(raw);
            return dt == null ? null : dt.atZone(ParsingSupport.SOUTH_AFRICA).toInstant();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String clean(String s) {
        String c = ParsingSupport.clean(s);
        return c == null || c.equalsIgnoreCase("N/A") || c.equalsIgnoreCase("NA") || c.equals("-") ? null : c;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }
}
