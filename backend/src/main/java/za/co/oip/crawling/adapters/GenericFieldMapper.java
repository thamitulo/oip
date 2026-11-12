package za.co.oip.crawling.adapters;

import tools.jackson.databind.JsonNode;
import za.co.oip.common.reference.Province;
import za.co.oip.crawling.adapter.AdapterResult.ItemError;
import za.co.oip.crawling.adapter.NormalisedOpportunity;
import za.co.oip.crawling.adapter.NormalisedOpportunity.DocumentLink;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.crawling.adapter.ParsingSupport.LocalClosing;
import za.co.oip.crawling.adapter.SourceDescriptor;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns raw extracted field values (from HTML, JSON or CSV) into a {@link NormalisedOpportunity},
 * driven by the source's parser configuration. Recognised raw field names:
 * referenceNumber, title, description, detailUrl, documentUrl, status, location, province, category,
 * closingDate, publishedDate, briefing, contactName, contactEmail, contactPhone.
 */
public final class GenericFieldMapper {

    private final SourceDescriptor source;
    private final List<String> dateFormats;
    private final EventType defaultEventType;
    private final Map<String, String> eventTypeKeywords;
    private final Map<String, String> statusMapping;
    private final NormalisedOpportunity.Submission submissionPolicy;
    private final List<String> requiredFields = new ArrayList<>();

    public GenericFieldMapper(SourceDescriptor source) {
        this.source = source;
        JsonNode cfg = source.config();
        this.dateFormats = new ArrayList<>();
        if (cfg != null && cfg.has("dateFormats")) {
            cfg.get("dateFormats").forEach(n -> dateFormats.add(n.asString()));
        }
        if (dateFormats.isEmpty()) {
            dateFormats.addAll(List.of("yyyy-MM-dd HH:mm", "yyyy-MM-dd", "d MMM yyyy HH:mm", "d MMM yyyy",
                    "d MMMM yyyy HH:mm", "d MMMM yyyy", "dd/MM/yyyy HH:mm", "dd/MM/yyyy"));
        }
        this.defaultEventType = EventType.valueOf(source.configText("defaultEventType", "OTHER"));
        this.eventTypeKeywords = stringMap(cfg, "eventTypeKeywords");
        Map<String, String> status = new LinkedHashMap<>();
        stringMap(cfg, "statusMapping").forEach((k, v) -> status.put(k.toLowerCase(), v));
        this.statusMapping = status;
        if (cfg != null && cfg.has("requireFields")) {
            cfg.get("requireFields").forEach(n -> requiredFields.add(n.asString()));
        }
        // A source can state how all of its opportunities are submitted (e.g. "apply online on this portal").
        JsonNode sub = cfg == null ? null : cfg.get("submission");
        this.submissionPolicy = sub == null || !sub.has("method") ? null : new NormalisedOpportunity.Submission(
                za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod.valueOf(sub.get("method").asString()),
                sub.has("channel") ? sub.get("channel").asString() : null,
                sub.has("address") ? sub.get("address").asString() : null, null,
                za.co.oip.opportunities.domain.OpportunityEnums.Provenance.SOURCE_POLICY,
                sub.has("evidence") ? sub.get("evidence").asString() : null);
    }

    /** Returns the mapped opportunity, or an item error explaining why the item was rejected. */
    public Result map(Map<String, String> raw, URI pageUri, LocalDate todayInSa) {
        String title = ParsingSupport.clean(raw.get("title"));
        String reference = ParsingSupport.clean(raw.get("referenceNumber"));
        String detailUrl = absolute(pageUri, raw.get("detailUrl"));
        String documentUrl = absolute(pageUri, raw.get("documentUrl"));
        for (String field : requiredFields) {
            if (ParsingSupport.clean(raw.get(field)) == null) {
                return Result.skip();
            }
        }
        if (title == null) {
            return Result.error(new ItemError("MISSING_TITLE", "Listing item has no title", detailUrl, reference));
        }

        NormalisedOpportunity.Builder b = NormalisedOpportunity.builder()
                .referenceNumber(reference)
                .title(ParsingSupport.abbreviate(title, 1000))
                .description(ParsingSupport.clean(raw.get("description")))
                .sourceCategoryLabel(ParsingSupport.clean(raw.get("category")))
                .locality(ParsingSupport.clean(raw.get("location")))
                .organisation(source.organisationName(), null)
                .sourceUrl(detailUrl != null ? detailUrl : documentUrl != null ? documentUrl : pageUri.toString())
                .evidenceUrl(pageUri.toString());

        String sourceReference = reference != null ? reference
                : detailUrl != null ? detailUrl
                : documentUrl != null ? documentUrl
                : "title:" + HexFormat.of().formatHex(title.toLowerCase().getBytes()).substring(0, Math.min(64, title.length() * 2));
        b.sourceReference(sourceReference);

        String provinceText = raw.getOrDefault("province", raw.get("location"));
        Province.parse(provinceText).ifPresent(p -> b.province(p.name()));

        EventType eventType = ParsingSupport.classifyEventType(title, eventTypeKeywords, defaultEventType);
        b.eventType(eventType);

        String closingRaw = ParsingSupport.clean(raw.get("closingDate"));
        Optional<LocalClosing> closing = ParsingSupport.parseLocal(closingRaw, dateFormats);
        if (closingRaw != null && closing.isEmpty()) {
            return Result.error(new ItemError("UNPARSEABLE_CLOSING_DATE",
                    "Could not parse closing date '" + closingRaw + "'", detailUrl, sourceReference));
        }
        closing.ifPresent(c -> b.closing(
                c.hasTime() ? c.dateTime().atZone(ParsingSupport.SOUTH_AFRICA).toInstant() : null,
                c.date(), c.hasTime(), closingRaw));

        ParsingSupport.parseLocal(ParsingSupport.clean(raw.get("publishedDate")), dateFormats)
                .ifPresent(p -> b.publishedAt(p.date().atStartOfDay(ParsingSupport.SOUTH_AFRICA).toInstant()));

        Optional<OpportunityStatus> status = ParsingSupport.mapStatus(raw.get("status"), statusMapping);
        if (status.isPresent()) {
            b.status(status.get(), false);
        } else if (closing.isPresent()) {
            b.status(closing.get().date().isBefore(todayInSa) ? OpportunityStatus.EXPIRED : OpportunityStatus.OPEN, true);
        }

        String briefing = ParsingSupport.clean(raw.get("briefing"));
        if (briefing != null) {
            Boolean compulsory = briefing.toLowerCase().contains("compulsory") ? Boolean.TRUE
                    : briefing.toLowerCase().contains("non-compulsory") || briefing.toLowerCase().contains("optional")
                    ? Boolean.FALSE : null;
            b.briefing(briefing, compulsory);
        }
        b.contact(ParsingSupport.clean(raw.get("contactName")), ParsingSupport.clean(raw.get("contactEmail")),
                ParsingSupport.clean(raw.get("contactPhone")));

        if (submissionPolicy != null) {
            b.submission(submissionPolicy);
        }
        if (documentUrl != null) {
            b.documents(List.of(new DocumentLink(title, documentUrl, ParsingSupport.fileFormat(documentUrl), null)));
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v != null) attributes.put(k, v.length() > 2000 ? v.substring(0, 2000) : v);
        });
        b.attributes(attributes);
        return Result.ok(b.build());
    }

    static String absolute(URI base, String href) {
        String h = ParsingSupport.clean(href);
        if (h == null || h.startsWith("javascript:") || h.startsWith("mailto:") || h.equals("#")) {
            return null;
        }
        try {
            return base.resolve(h.replace(" ", "%20")).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, String> stringMap(JsonNode cfg, String field) {
        Map<String, String> map = new LinkedHashMap<>();
        if (cfg != null && cfg.has(field) && cfg.get(field).isObject()) {
            cfg.get(field).properties().forEach(e -> map.put(e.getKey(), e.getValue().asString()));
        }
        return map;
    }

    /** Either a mapped opportunity or an item error. */
    public record Result(NormalisedOpportunity opportunity, ItemError error) {
        static Result ok(NormalisedOpportunity o) {
            return new Result(o, null);
        }

        /** The item is not an opportunity (e.g. a form listed beside adverts); ignore it silently. */
        static Result skip() {
            return new Result(null, null);
        }

        public boolean skipped() {
            return opportunity == null && error == null;
        }

        static Result error(ItemError e) {
            return new Result(null, e);
        }
    }
}
