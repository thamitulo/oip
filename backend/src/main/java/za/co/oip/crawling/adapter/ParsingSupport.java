package za.co.oip.crawling.adapter;

import za.co.oip.opportunities.domain.OpportunityEnums.DocumentType;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Deterministic parsing helpers shared by adapters. */
public final class ParsingSupport {

    public static final ZoneId SOUTH_AFRICA = ZoneId.of("Africa/Johannesburg");

    private ParsingSupport() {
    }

    /** A parsed date with an optional time, in South African local time. */
    public record LocalClosing(LocalDate date, LocalDateTime dateTime) {
        public boolean hasTime() {
            return dateTime != null;
        }
    }

    /**
     * Parses a date or date-time written in one of the given patterns. Handles common variations such
     * as "Sept" (instead of "Sep") and ordinal suffixes ("3rd").
     */
    public static Optional<LocalClosing> parseLocal(String raw, List<String> patterns) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String cleaned = raw.trim().replaceAll("\\s+", " ")
                .replaceAll("(?i)\\bSept\\b", "Sep")
                .replaceAll("(?i)(\\d)(st|nd|rd|th)\\b", "$1");
        for (String pattern : patterns) {
            DateTimeFormatter formatter = new DateTimeFormatterBuilder().parseCaseInsensitive()
                    .appendPattern(pattern).toFormatter(Locale.ENGLISH);
            boolean hasTime = pattern.contains("H") || pattern.contains("h");
            try {
                if (hasTime) {
                    LocalDateTime dt = LocalDateTime.parse(cleaned, formatter);
                    return Optional.of(new LocalClosing(dt.toLocalDate(), dt));
                }
                return Optional.of(new LocalClosing(LocalDate.parse(cleaned, formatter), null));
            } catch (DateTimeParseException ignored) {
                // try the next pattern
            }
        }
        return Optional.empty();
    }

    /** Classifies an event type from free text by keyword, in priority order. */
    public static EventType classifyEventType(String text, Map<String, String> keywordMap, EventType fallback) {
        if (text != null) {
            String upper = text.toUpperCase(Locale.ROOT);
            for (Map.Entry<String, String> e : keywordMap.entrySet()) {
                if (upper.matches("(?s).*\\b" + java.util.regex.Pattern.quote(e.getKey().toUpperCase(Locale.ROOT)) + "\\b.*")) {
                    return EventType.valueOf(e.getValue());
                }
            }
        }
        return fallback;
    }

    /** Standard phrase-based event-type classification (used when a source gives a method description). */
    public static EventType classifyProcurementMethod(String text, EventType fallback) {
        if (text == null) {
            return fallback;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("quotation")) return EventType.RFQ;
        if (t.contains("proposal")) return EventType.RFP;
        if (t.contains("request for information")) return EventType.RFI;
        if (t.contains("expression of interest")) return EventType.EOI;
        if (t.contains("bid") || t.contains("tender")) return EventType.TENDER;
        return fallback;
    }

    public static Optional<OpportunityStatus> mapStatus(String raw, Map<String, String> mapping) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        String mapped = mapping.get(key);
        if (mapped != null) {
            return Optional.of(OpportunityStatus.valueOf(mapped));
        }
        return switch (key) {
            case "open", "active", "published", "current" -> Optional.of(OpportunityStatus.OPEN);
            case "closed", "complete", "completed" -> Optional.of(OpportunityStatus.CLOSED);
            case "awarded" -> Optional.of(OpportunityStatus.AWARDED);
            case "cancelled", "canceled", "withdrawn" -> Optional.of(OpportunityStatus.CANCELLED);
            default -> Optional.empty();
        };
    }

    /** Classifies a document from its title or file name. */
    public static DocumentType classifyDocument(String title) {
        if (title == null) {
            return DocumentType.OTHER;
        }
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("addend") || t.contains("corrigend")) return DocumentType.ADDENDUM;
        if (t.contains("bill of quantit") || t.matches("(?s).*\\bboq\\b.*")) return DocumentType.BILL_OF_QUANTITIES;
        if (t.contains("pricing schedule") || t.contains("price schedule")) return DocumentType.PRICING_SCHEDULE;
        if (t.contains("scope of work") || t.contains("terms of reference")) return DocumentType.SCOPE_OF_WORK;
        if (t.contains("specification")) return DocumentType.TECHNICAL_SPECIFICATION;
        if (t.contains("returnable")) return DocumentType.RETURNABLE_SCHEDULE;
        if (t.contains("briefing")) return DocumentType.SITE_BRIEFING_NOTICE;
        if (t.contains("conditions of contract") || t.contains("contract conditions")) return DocumentType.CONTRACT_CONDITIONS;
        if (t.contains("advert")) return DocumentType.ADVERT;
        if (t.contains("tender") || t.contains("bid document") || t.contains("rfq") || t.contains("rfp")
                || t.contains("invitation to")) return DocumentType.TENDER_DOCUMENT;
        return DocumentType.OTHER;
    }

    public static String fileFormat(String urlOrName) {
        if (urlOrName == null) {
            return null;
        }
        String path = urlOrName.toLowerCase(Locale.ROOT).replaceAll("[?#].*$", "");
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < path.lastIndexOf('/')) {
            return null;
        }
        String ext = path.substring(dot + 1);
        return ext.matches("[a-z0-9]{1,5}") ? ext : null;
    }

    /** Collapses whitespace and trims; returns null for blank input. */
    public static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    /** Shortens text to at most {@code max} characters at a word boundary. */
    public static String abbreviate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        int cut = s.lastIndexOf(' ', max - 1);
        return s.substring(0, cut > max / 2 ? cut : max - 1).trim() + "…";
    }
}
