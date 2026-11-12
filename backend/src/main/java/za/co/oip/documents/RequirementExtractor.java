package za.co.oip.documents;

import org.springframework.stereotype.Component;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic extraction of procurement requirements from document text. Every finding carries the sentence
 * it came from, so a person can check it. Nothing is guessed: if a pattern is not present, nothing is returned.
 */
@Component
public class RequirementExtractor {

    public enum Type {
        CIDB_GRADING, BBBEE_LEVEL, PREFERENCE_POINT_SYSTEM, CSD_REGISTRATION, TAX_COMPLIANCE, PROFESSIONAL_REGISTRATION,
        CERTIFICATION, COMPULSORY_BRIEFING, LOCAL_CONTENT
    }

    public record Finding(Type type, String value, Boolean mandatory, String evidence) {
    }

    public record SubmissionFinding(SubmissionMethod method, String evidence) {
    }

    public record ClosingFinding(LocalDate date, LocalDateTime dateTime, String evidence) {
    }

    public record Result(List<Finding> requirements, Optional<SubmissionFinding> submission, Optional<ClosingFinding> closing) {
    }

    private static final Pattern CIDB = Pattern.compile(
            "(?i)\\bCIDB\\b[^.\\n]{0,80}?\\b(?:grad(?:e|ing)|level|designation)?\\s*(?:of\\s*)?([1-9])\\s*([A-Z]{2})\\b");
    private static final Pattern CIDB_REVERSED = Pattern.compile("(?i)\\b([1-9])\\s*(CE|GB|ME|EP|EB|SO|SQ|SH|SI|SJ|SK|SL|SM|SN)\\b\\s*(?:or higher|or above|and above|PE)?[^.\\n]{0,40}\\bCIDB\\b");
    private static final Pattern BBBEE_LEVEL = Pattern.compile("(?i)\\bB-?BBEE\\b[^.\\n]{0,60}?\\blevel\\s*([1-8])\\b");
    private static final Pattern PREFERENCE = Pattern.compile("(?i)\\b(80\\s*/\\s*20|90\\s*/\\s*10)\\b[^.\\n]{0,40}\\bpreference");
    private static final Pattern CSD = Pattern.compile("(?i)\\b(central supplier database|CSD)\\b");
    private static final Pattern TAX = Pattern.compile("(?i)\\b(tax compliance status|tax clearance|SARS (?:PIN|e-?filing))\\b");
    private static final Pattern PROFESSIONAL = Pattern.compile(
            "(?i)\\bregistered with\\s+(?:the\\s+)?(ECSA|SACPCMP|SACAP|SACQSP|SAICA|IRBA|PSIRA|SACNASP|SAIPA|HPCSA|SACPVP|LPC|Legal Practice Council|Engineering Council of South Africa)\\b");
    private static final Pattern ISO = Pattern.compile("(?i)\\b(ISO\\s?\\d{4,5}(?::\\d{4})?|OHSAS\\s?18001)\\b");
    /** Negative lookbehind: "non-compulsory", "non compulsory" and "not compulsory" are not compulsory. */
    private static final Pattern BRIEFING = Pattern.compile(
            "(?i)(?<!non[- ])(?<!non)(?<!not )\\b(compulsory|mandatory)\\s+(?:site\\s+|clarification\\s+)?(briefing|site (?:inspection|visit|meeting))\\b");
    private static final Pattern LOCAL_CONTENT = Pattern.compile("(?i)\\blocal (?:content|production)\\b[^.\\n]{0,60}?(\\d{2,3}\\s?%)");
    private static final Pattern MANDATORY = Pattern.compile("(?i)\\b(mandatory|must|shall|compulsory|required|disqualif)");

    private static final Pattern ELECTRONIC = Pattern.compile(
            "(?i)\\b(submitted|submit|submission|lodged)\\b[^.\\n]{0,80}\\b(electronic(?:ally)?|e-?tender(?:ing)?|online|via (?:the )?(?:portal|e-?mail)|by e-?mail|to the following e-?mail)\\b");
    private static final Pattern PHYSICAL = Pattern.compile(
            "(?i)\\b(tender box|bid box|deposited (?:in|into)|hand[- ]deliver(?:ed|y)?|delivered by hand|sealed envelope)\\b");
    private static final Pattern CLOSING = Pattern.compile(
            "(?i)\\b(?:closing date(?: and time)?|closes on|must reach[^.\\n]{0,40}by|submitted (?:no later than|before|by))\\b[^0-9\\n]{0,30}"
                    + "(\\d{1,2}(?:st|nd|rd|th)?\\s+[A-Za-z]{3,9}\\.?\\s+\\d{4}|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{4})"
                    + "(?:[^0-9\\n]{0,25}(\\d{1,2})[:hH.](\\d{2}))?");

    public Result extract(String text) {
        if (text == null || text.isBlank()) {
            return new Result(List.of(), Optional.empty(), Optional.empty());
        }
        Map<String, Finding> found = new LinkedHashMap<>();
        for (Matcher m = CIDB.matcher(text); m.find(); ) {
            add(found, Type.CIDB_GRADING, m.group(1) + m.group(2).toUpperCase(Locale.ROOT), text, m);
        }
        for (Matcher m = CIDB_REVERSED.matcher(text); m.find(); ) {
            add(found, Type.CIDB_GRADING, m.group(1) + m.group(2).toUpperCase(Locale.ROOT), text, m);
        }
        for (Matcher m = BBBEE_LEVEL.matcher(text); m.find(); ) {
            add(found, Type.BBBEE_LEVEL, "Level " + m.group(1), text, m);
        }
        java.util.Set<String> systems = new java.util.LinkedHashSet<>();
        String preferenceEvidence = null;
        for (Matcher m = PREFERENCE.matcher(text); m.find(); ) {
            systems.add(m.group(1).replaceAll("\\s", ""));
            if (preferenceEvidence == null) preferenceEvidence = sentence(text, m.start(), m.end());
        }
        if (systems.size() == 1) {
            found.put("PREFERENCE:" + systems.iterator().next(), new Finding(Type.PREFERENCE_POINT_SYSTEM,
                    systems.iterator().next(), null, preferenceEvidence));
        } else if (systems.size() > 1) {
            // Standard bid forms list both systems as alternatives; the invitation states which one applies.
            found.put("PREFERENCE:both", new Finding(Type.PREFERENCE_POINT_SYSTEM,
                    String.join(" or ", systems) + " (the form lists both; check which applies)", null, preferenceEvidence));
        }
        firstMatch(CSD, text).ifPresent(m -> add(found, Type.CSD_REGISTRATION, "Central Supplier Database", text, m));
        firstMatch(TAX, text).ifPresent(m -> add(found, Type.TAX_COMPLIANCE, "Tax compliance status", text, m));
        for (Matcher m = PROFESSIONAL.matcher(text); m.find(); ) {
            add(found, Type.PROFESSIONAL_REGISTRATION, m.group(1).toUpperCase(Locale.ROOT), text, m);
        }
        for (Matcher m = ISO.matcher(text); m.find(); ) {
            add(found, Type.CERTIFICATION, m.group(1).toUpperCase(Locale.ROOT).replace(" ", " "), text, m);
        }
        firstMatch(BRIEFING, text).ifPresent(m -> found.putIfAbsent("COMPULSORY_BRIEFING",
                new Finding(Type.COMPULSORY_BRIEFING, "Compulsory " + m.group(2).toLowerCase(Locale.ROOT), true, sentence(text, m.start(), m.end()))));
        for (Matcher m = LOCAL_CONTENT.matcher(text); m.find(); ) {
            add(found, Type.LOCAL_CONTENT, m.group(1).replace(" ", ""), text, m);
        }
        return new Result(new ArrayList<>(found.values()), submission(text), closing(text));
    }

    Optional<SubmissionFinding> submission(String text) {
        Optional<Matcher> electronic = firstMatch(ELECTRONIC, text);
        Optional<Matcher> physical = firstMatch(PHYSICAL, text);
        if (electronic.isPresent() && physical.isPresent()) {
            return Optional.of(new SubmissionFinding(SubmissionMethod.ELECTRONIC_OR_PHYSICAL,
                    sentence(text, electronic.get().start(), electronic.get().end()) + " … "
                            + sentence(text, physical.get().start(), physical.get().end())));
        }
        return electronic.map(m -> new SubmissionFinding(SubmissionMethod.ELECTRONIC, sentence(text, m.start(), m.end())))
                .or(() -> physical.map(m -> new SubmissionFinding(SubmissionMethod.PHYSICAL, sentence(text, m.start(), m.end()))));
    }

    Optional<ClosingFinding> closing(String text) {
        Matcher m = CLOSING.matcher(text);
        while (m.find()) {
            var date = ParsingSupport.parseLocal(m.group(1),
                    List.of("d MMMM yyyy", "d MMM yyyy", "yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy"));
            if (date.isEmpty()) continue;
            LocalDateTime dateTime = null;
            if (m.group(2) != null) {
                int h = Integer.parseInt(m.group(2));
                int min = Integer.parseInt(m.group(3));
                if (h < 24 && min < 60) dateTime = date.get().date().atTime(h, min);
            }
            return Optional.of(new ClosingFinding(date.get().date(), dateTime, sentence(text, m.start(), m.end())));
        }
        return Optional.empty();
    }

    private static void add(Map<String, Finding> found, Type type, String value, String text, Matcher m) {
        String s = sentence(text, m.start(), m.end());
        found.putIfAbsent(type + ":" + value, new Finding(type, value, MANDATORY.matcher(s).find() ? Boolean.TRUE : null, s));
    }

    private static Optional<Matcher> firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? Optional.of(m) : Optional.empty();
    }

    /** The sentence (bounded to 400 chars) around a match. */
    static String sentence(String text, int start, int end) {
        int from = start;
        while (from > 0 && start - from < 200 && ".\n".indexOf(text.charAt(from - 1)) < 0) from--;
        int to = end;
        while (to < text.length() && to - end < 200 && ".\n".indexOf(text.charAt(to)) < 0) to++;
        return text.substring(from, Math.min(text.length(), to + 1)).replaceAll("\\s+", " ").trim();
    }
}
