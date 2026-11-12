package za.co.oip.crawling.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal robots.txt evaluation following RFC 9309: the most specific matching user-agent group
 * applies (falling back to {@code *}); within it the longest matching rule wins and Allow wins ties.
 * Supports the {@code *} and {@code $} wildcards.
 */
public final class RobotsTxt {

    public static final RobotsTxt ALLOW_ALL = new RobotsTxt(List.of());

    private record Rule(boolean allow, String pattern) {
    }

    private record Group(List<String> agents, List<Rule> rules) {
    }

    private final List<Group> groups;

    private RobotsTxt(List<Group> groups) {
        this.groups = groups;
    }

    public static RobotsTxt parse(String content) {
        List<Group> groups = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        boolean lastWasAgent = false;
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.replaceAll("#.*$", "").trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "user-agent" -> {
                    if (!lastWasAgent && !agents.isEmpty()) {
                        groups.add(new Group(List.copyOf(agents), List.copyOf(rules)));
                        agents.clear();
                        rules.clear();
                    }
                    agents.add(value.toLowerCase(Locale.ROOT));
                    lastWasAgent = true;
                }
                case "allow", "disallow" -> {
                    lastWasAgent = false;
                    if (!agents.isEmpty() && !value.isEmpty()) {
                        rules.add(new Rule(key.equals("allow"), value));
                    }
                }
                default -> lastWasAgent = false;
            }
        }
        if (!agents.isEmpty()) {
            groups.add(new Group(List.copyOf(agents), List.copyOf(rules)));
        }
        return new RobotsTxt(groups);
    }

    public boolean isAllowed(String userAgentToken, String pathAndQuery) {
        String token = userAgentToken.toLowerCase(Locale.ROOT);
        List<Rule> applicable = null;
        for (Group g : groups) {
            if (g.agents().stream().anyMatch(a -> !a.equals("*") && token.startsWith(a))) {
                applicable = merge(applicable, g.rules());
            }
        }
        if (applicable == null) {
            for (Group g : groups) {
                if (g.agents().contains("*")) {
                    applicable = merge(applicable, g.rules());
                }
            }
        }
        if (applicable == null || applicable.isEmpty()) {
            return true;
        }
        String path = pathAndQuery.isEmpty() ? "/" : pathAndQuery;
        Rule best = null;
        for (Rule r : applicable) {
            if (matches(r.pattern(), path)) {
                if (best == null || r.pattern().length() > best.pattern().length()
                        || (r.pattern().length() == best.pattern().length() && r.allow())) {
                    best = r;
                }
            }
        }
        return best == null || best.allow();
    }

    private static List<Rule> merge(List<Rule> a, List<Rule> b) {
        List<Rule> out = a == null ? new ArrayList<>() : new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    static boolean matches(String pattern, String path) {
        boolean anchored = pattern.endsWith("$");
        String p = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        StringBuilder regex = new StringBuilder("^");
        for (char c : p.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        if (anchored) {
            regex.append('$');
        }
        return java.util.regex.Pattern.compile(regex.toString()).matcher(path).find();
    }
}
