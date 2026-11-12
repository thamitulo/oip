package za.co.oip.classification;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic detection of public-sector opportunities whose subject is mining (e.g. mine rehabilitation,
 * geoscience surveys for mineral resources, services to state mining entities). Returns the matched phrase as
 * evidence so that the classification is explainable; it never relies on AI.
 */
@Component
public class MiningRelevanceClassifier {

    /** Whole-word patterns that indicate a mining subject. Deliberately conservative to avoid false positives. */
    private static final List<Pattern> SUBJECT_PATTERNS = List.of(
            Pattern.compile("\\b(?:mine|mines|mining|colliery|collieries|mineshaft)\\b"),
            Pattern.compile("\\bminerals?\\b(?! water)"),
            Pattern.compile("\\b(?:tailings|smelter|smelting|ore body|orebody|open[- ]pit|underground mine|(?:mineral|ore|coal|metals?) beneficiation)\\b"),
            Pattern.compile("\\b(?:platinum group metals?|pgms?)\\b"),
            Pattern.compile("\\b(?:derelict and ownerless|ownerless and derelict)\\b"),
            Pattern.compile("\\bquarr(?:y|ies)\\b"));

    /** Public entities whose mandate is mining or mineral resources. */
    private static final List<Pattern> ORGANISATION_PATTERNS = List.of(
            Pattern.compile("\\bmintek\\b"),
            Pattern.compile("\\bcouncil for geoscience\\b"),
            Pattern.compile("\\bmining qualifications authority\\b"),
            Pattern.compile("\\balexkor\\b"),
            Pattern.compile("\\bmineral resources\\b"),
            Pattern.compile("\\bmine health and safety\\b"));

    public Optional<String> classify(String title, String description, String organisationName) {
        String org = lower(organisationName);
        for (Pattern p : ORGANISATION_PATTERNS) {
            Matcher m = p.matcher(org);
            if (m.find()) {
                return Optional.of("Procuring entity: " + organisationName);
            }
        }
        String text = lower(title) + " \n " + lower(description);
        for (Pattern p : SUBJECT_PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return Optional.of("Mentions \"" + excerpt(text, m.start(), m.end()) + "\"");
            }
        }
        return Optional.empty();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String excerpt(String text, int start, int end) {
        int from = Math.max(0, start - 40);
        int to = Math.min(text.length(), end + 40);
        return (from > 0 ? "…" : "") + text.substring(from, to).replaceAll("\\s+", " ").trim() + (to < text.length() ? "…" : "");
    }
}
