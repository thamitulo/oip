package za.co.oip.matching;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, explainable scoring of an opportunity against a company profile. Every point of the score is
 * traceable to a listed reason. Unknown information is scored neutrally and reported as unknown — it is never
 * assumed to be satisfied. Missing mandatory requirements are returned as disqualifiers, separately from the
 * score, and are never hidden by it. A high score does not mean the company is eligible.
 */
@Component
public class MatchingEngine {

    public enum Label { STRONG_MATCH, POSSIBLE_MATCH, WEAK_MATCH, REQUIRES_MANUAL_REVIEW }

    /** What the engine needs to know about the company. */
    public record Profile(List<String> services, List<String> products, List<String> industries, List<String> commodities,
                          List<String> operatingRegions, List<Capability> capabilities, List<String> certifications,
                          List<Registration> registrations, String cidbGrading, Integer bbbeeLevel) {
        public boolean isEmpty() {
            return services.isEmpty() && products.isEmpty() && industries.isEmpty() && capabilities.isEmpty();
        }
    }

    public record Capability(String name, String description) {
    }

    public record Registration(String type, String body, String number) {
    }

    public record Requirement(String type, String value, Boolean mandatory, String evidence) {
    }

    /** What the engine needs to know about the opportunity. */
    public record Opportunity(String title, String description, String categoryLabel, String province, boolean miningRelated,
                              String sector, Long daysUntilClosing, Boolean compulsoryBriefing, List<Requirement> requirements) {
    }

    public record Weights(int services, int industry, int capability, int geography, int registrations, int other,
                          int strongThreshold, int possibleThreshold) {
        public static final Weights DEFAULT = new Weights(30, 20, 20, 10, 10, 10, 70, 45);
    }

    public record Result(int score, Label label, Map<String, Double> dimensions, List<String> matched, List<String> missing,
                         List<String> disqualifiers) {
    }

    private static final Set<String> STOPWORDS = Set.of("the", "and", "for", "of", "to", "a", "an", "in", "on", "at", "by",
            "with", "or", "as", "be", "is", "are", "from", "this", "that", "provision", "provide", "providing", "supply",
            "services", "service", "appointment", "appoint", "service provider", "provider", "period", "years", "year",
            "months", "request", "quotation", "proposal", "bid", "tender", "rfq", "rfp", "panel", "required", "various",
            "delivery", "deliver", "including", "related", "general", "other", "all", "its", "their", "within");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    /**
     * Typical terms for common industries, so that a tender can align with an industry without naming it
     * ("software development" belongs to information technology). Keys are matched against normalised profile
     * industry names; values are stemmed tokens.
     */
    static final Map<String, Set<String>> INDUSTRY_TERMS = Map.ofEntries(
            Map.entry("information technology", Set.of("software", "system", "ict", "digital", "network", "computer", "data",
                    "application", "cyber", "website", "erp", "server", "cloud", "it")),
            Map.entry("ict", Set.of("software", "system", "ict", "digital", "network", "computer", "data", "telecommunication")),
            Map.entry("construction", Set.of("construction", "building", "civil", "road", "renovation", "refurbishment", "paving",
                    "plumb", "roof", "concrete", "earthwork")),
            Map.entry("engineering", Set.of("engineering", "mechanical", "electrical", "structural", "design", "maintenance")),
            Map.entry("security", Set.of("security", "guard", "guarding", "surveillance", "cctv", "access")),
            Map.entry("cleaning", Set.of("cleaning", "hygiene", "sanitation", "pest", "waste")),
            Map.entry("consult", Set.of("consulting", "advisory", "consultant", "audit", "assessment", "study", "strategy")),
            Map.entry("professional", Set.of("consulting", "advisory", "consultant", "audit", "legal", "accounting", "study")),
            Map.entry("transport", Set.of("transport", "logistic", "fleet", "vehicle", "courier", "freight")),
            Map.entry("health", Set.of("medical", "health", "hospital", "clinic", "pharmaceutical", "nursing")),
            Map.entry("catering", Set.of("catering", "food", "meal", "kitchen")),
            Map.entry("training", Set.of("training", "skill", "learnership", "education", "course")),
            Map.entry("mining", Set.of("mine", "mining", "mineral", "colliery", "shaft", "tailing", "smelter")),
            Map.entry("energy", Set.of("energy", "electricity", "solar", "power", "generator", "fuel")),
            Map.entry("water", Set.of("water", "sanitation", "sewer", "wastewater", "borehole")));
    private static final Pattern CIDB = Pattern.compile("(?i)([1-9])\\s*([A-Z]{2})");

    public Result score(Profile p, Opportunity o, Weights w) {
        String text = normalise(String.join(" ", nz(o.title()), nz(o.description()), nz(o.categoryLabel())));
        Set<String> tokens = tokens(text);
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> disqualifiers = new ArrayList<>();
        Map<String, Double> dims = new LinkedHashMap<>();

        // 1. Services and products
        List<String> offerings = new ArrayList<>(p.services());
        offerings.addAll(p.products());
        dims.put("services", bestTermScore(offerings, text, tokens, "Service/product", matched));
        if (offerings.isEmpty()) missing.add("No services or products in your profile");

        // 2. Industry
        double industry = bestTermScore(p.industries(), text, tokens, "Industry", matched);
        boolean miningCompany = p.industries().stream().anyMatch(i -> normalise(i).contains("mining"))
                || !p.commodities().isEmpty();
        if (o.miningRelated() && miningCompany) {
            industry = 1.0;
            matched.add("Industry: mining-related opportunity and your profile covers mining");
        }
        if (industry < 0.8) {
            industry = Math.max(industry, lexiconScore(p.industries(), tokens, matched));
        }
        double commodity = bestTermScore(p.commodities(), text, tokens, "Commodity", matched);
        dims.put("industry", Math.max(industry, commodity));

        // 3. Capabilities (name and description)
        List<String> capabilityTerms = new ArrayList<>();
        for (Capability c : p.capabilities()) capabilityTerms.add(c.name());
        double capability = bestTermScore(capabilityTerms, text, tokens, "Capability", matched);
        if (capability < 0.5) {
            for (Capability c : p.capabilities()) {
                if (c.description() == null) continue;
                Set<String> descTokens = tokens(normalise(c.description()));
                descTokens.retainAll(tokens);
                if (descTokens.size() >= 3) {
                    capability = Math.max(capability, 0.5);
                    matched.add("Capability: \"" + c.name() + "\" shares terms " + descTokens.stream().limit(5).toList());
                    break;
                }
            }
        }
        dims.put("capability", capability);

        // 4. Geography
        double geography;
        if (o.province() == null) {
            geography = 0.5;
            missing.add("Location not published by the source (check the documents)");
        } else if (p.operatingRegions().isEmpty()) {
            geography = 0.5;
            missing.add("No operating regions in your profile");
        } else if (p.operatingRegions().contains("NATIONAL") || p.operatingRegions().contains(o.province())) {
            geography = 1.0;
            matched.add("Location: " + o.province() + " is within your operating regions");
        } else {
            geography = 0.0;
            missing.add("Location " + o.province() + " is outside your operating regions");
        }
        dims.put("geography", geography);

        // 5. Registrations and certifications against identified requirements
        List<Requirement> regReqs = o.requirements().stream()
                .filter(r -> Set.of("CIDB_GRADING", "CSD_REGISTRATION", "TAX_COMPLIANCE", "PROFESSIONAL_REGISTRATION", "CERTIFICATION")
                        .contains(r.type()))
                .toList();
        if (regReqs.isEmpty()) {
            dims.put("registrations", 0.5);
            missing.add("Registration/certification requirements not identified yet (read the documents)");
        } else {
            int ok = 0;
            for (Requirement r : regReqs) {
                Boolean satisfied = satisfies(p, r);
                String req = describe(r);
                if (Boolean.TRUE.equals(satisfied)) {
                    ok++;
                    matched.add("Requirement met (self-declared): " + req);
                } else if (Boolean.TRUE.equals(r.mandatory())) {
                    disqualifiers.add("Mandatory: " + req + " — " + (satisfied == null ? "not in your profile" : "your profile does not meet it"));
                } else {
                    missing.add("Requirement: " + req + (satisfied == null ? " — not in your profile" : " — not met"));
                }
            }
            dims.put("registrations", ok / (double) regReqs.size());
        }

        // 6. Other eligibility factors
        double other = 0.7;
        for (Requirement r : o.requirements()) {
            if ("BBBEE_LEVEL".equals(r.type())) {
                Integer required = digit(r.value());
                if (p.bbbeeLevel() == null) {
                    other -= 0.3;
                    missing.add("B-BBEE " + r.value() + " requested — your level is not in your profile");
                } else if (required != null && p.bbbeeLevel() <= required) {
                    other += 0.3;
                    matched.add("B-BBEE: your Level " + p.bbbeeLevel() + " meets " + r.value());
                } else if (required != null) {
                    other -= 0.5;
                    (Boolean.TRUE.equals(r.mandatory()) ? disqualifiers : missing)
                            .add("B-BBEE " + r.value() + " required — your profile shows Level " + p.bbbeeLevel());
                }
            }
        }
        if (Boolean.TRUE.equals(o.compulsoryBriefing())) {
            missing.add("Action: attendance at the compulsory briefing is required");
        }
        if (o.daysUntilClosing() != null && o.daysUntilClosing() <= 2 && o.daysUntilClosing() >= 0) {
            other -= 0.2;
            missing.add("Closes in " + o.daysUntilClosing() + " day(s): very little time to prepare");
        }
        dims.put("other", clamp(other));

        double weighted = w.services() * dims.get("services") + w.industry() * dims.get("industry")
                + w.capability() * dims.get("capability") + w.geography() * dims.get("geography")
                + w.registrations() * dims.get("registrations") + w.other() * dims.get("other");
        // Without any topical alignment, neutral dimensions alone must not make an opportunity look relevant.
        boolean topical = dims.get("services") > 0 || dims.get("industry") > 0 || dims.get("capability") > 0;
        int score = (int) Math.round(topical ? weighted : Math.min(weighted, w.possibleThreshold() - 1));
        if (p.isEmpty()) {
            score = 0;
            missing.add(0, "Complete your company profile to get meaningful matches");
        }
        Label label = !disqualifiers.isEmpty() ? Label.REQUIRES_MANUAL_REVIEW
                : score >= w.strongThreshold() ? Label.STRONG_MATCH
                : score >= w.possibleThreshold() ? Label.POSSIBLE_MATCH : Label.WEAK_MATCH;
        return new Result(Math.max(0, Math.min(100, score)), label, dims, matched, missing, disqualifiers);
    }

    /** Industry alignment through typical terms: 0.8 for two or more terms, 0.5 for one. Records the terms found. */
    private static double lexiconScore(List<String> industries, Set<String> tokens, List<String> matched) {
        double best = 0;
        String bestIndustry = null;
        Set<String> bestHits = Set.of();
        for (String industry : industries) {
            String norm = normalise(industry);
            for (Map.Entry<String, Set<String>> e : INDUSTRY_TERMS.entrySet()) {
                if (!(" " + norm + " ").contains(" " + e.getKey()) && !norm.equals(e.getKey())) continue;
                Set<String> hits = new java.util.TreeSet<>(e.getValue());
                hits.retainAll(tokens);
                double s = hits.size() >= 2 ? 0.8 : hits.size() == 1 ? 0.5 : 0;
                if (s > best) {
                    best = s;
                    bestIndustry = industry;
                    bestHits = hits;
                }
            }
        }
        if (bestIndustry != null) {
            matched.add("Industry: \"" + bestIndustry + "\" (typical terms " + bestHits + ")");
        }
        return best;
    }

    /** TRUE when the profile declares something satisfying the requirement, FALSE when it declares something that does not, null when unknown. */
    static Boolean satisfies(Profile p, Requirement r) {
        String value = nz(r.value());
        return switch (r.type()) {
            case "CIDB_GRADING" -> cidbSatisfies(p, value);
            case "CSD_REGISTRATION" -> hasRegistration(p, "CSD", null) ? Boolean.TRUE : null;
            case "TAX_COMPLIANCE" -> hasRegistration(p, "TAX", null) ? Boolean.TRUE : null;
            case "PROFESSIONAL_REGISTRATION" -> hasRegistration(p, "PROFESSIONAL_BODY", value) ? Boolean.TRUE : null;
            case "CERTIFICATION" -> p.certifications().stream().anyMatch(c -> normalise(c).replace(" ", "")
                    .contains(normalise(value).replace(" ", ""))) ? Boolean.TRUE : null;
            default -> null;
        };
    }

    private static Boolean cidbSatisfies(Profile p, String required) {
        Matcher req = CIDB.matcher(required);
        if (!req.find()) return null;
        List<String> declared = new ArrayList<>();
        if (p.cidbGrading() != null) declared.add(p.cidbGrading());
        p.registrations().stream().filter(r -> "CIDB".equals(r.type()) && r.number() != null).forEach(r -> declared.add(r.number()));
        if (declared.isEmpty()) return null;
        boolean anyClass = false;
        for (String d : declared) {
            Matcher m = CIDB.matcher(d);
            while (m.find()) {
                if (m.group(2).equalsIgnoreCase(req.group(2))) {
                    anyClass = true;
                    if (Integer.parseInt(m.group(1)) >= Integer.parseInt(req.group(1))) return Boolean.TRUE;
                }
            }
        }
        return anyClass ? Boolean.FALSE : null;
    }

    private static boolean hasRegistration(Profile p, String type, String body) {
        return p.registrations().stream().anyMatch(r -> type.equals(r.type())
                && (body == null || normalise(nz(r.body())).contains(normalise(body))));
    }

    private static String describe(Requirement r) {
        return switch (r.type()) {
            case "CIDB_GRADING" -> "CIDB grading " + r.value();
            case "CSD_REGISTRATION" -> "Central Supplier Database registration";
            case "TAX_COMPLIANCE" -> "Tax compliance status";
            case "PROFESSIONAL_REGISTRATION" -> "Registration with " + r.value();
            case "CERTIFICATION" -> r.value() + " certification";
            default -> r.type() + " " + nz(r.value());
        };
    }

    /** Best match of any term: 1.0 phrase, 0.8 for ≥75% of its words, 0.5 for ≥50%. Records what matched. */
    private static double bestTermScore(List<String> terms, String text, Set<String> tokens, String kind, List<String> matched) {
        double best = 0;
        String bestTerm = null;
        String how = null;
        for (String term : terms) {
            String norm = normalise(term);
            Set<String> termTokens = tokens(norm);
            if (termTokens.isEmpty()) continue;
            double s;
            String h;
            if (norm.length() >= 4 && (" " + text + " ").contains(" " + norm + " ")) {
                s = 1.0;
                h = "phrase found";
            } else {
                Set<String> hit = new HashSet<>(termTokens);
                hit.retainAll(tokens);
                double coverage = hit.size() / (double) termTokens.size();
                s = coverage >= 0.75 ? 0.8 : coverage >= 0.5 ? 0.5 : 0;
                h = "words " + hit;
            }
            if (s > best) {
                best = s;
                bestTerm = term;
                how = h;
            }
        }
        if (bestTerm != null) {
            matched.add(kind + ": \"" + bestTerm + "\" (" + how + ")");
        }
        return best;
    }

    static String normalise(String s) {
        return nz(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    static Set<String> tokens(String normalised) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(normalised);
        while (m.find()) {
            String w = m.group();
            if (w.length() < 3 || STOPWORDS.contains(w)) continue;
            out.add(stem(w));
        }
        return out;
    }

    private static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && w.endsWith("es")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    private static Integer digit(String s) {
        Matcher m = Pattern.compile("([1-8])").matcher(nz(s));
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
