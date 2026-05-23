package io.jcgraph.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The source/sink catalog used for taint-style vulnerability hunting.
 *
 * <p>Loads {@code /sinks.txt} from the classpath (overridable with
 * {@code -Djcgraph.rules=<path>}). Each rule matches a method by its declared
 * owner + name, which is exactly what the call graph stores as a CALLS target,
 * so a rule on {@code HttpServletRequest#getParameter} matches every call site
 * that invokes it through that interface.</p>
 */
public final class RuleCatalog {

    public enum Kind { SOURCE, SINK }

    /** One catalog entry: a dangerous (sink) or untrusted-input (source) method. */
    public static final class Rule {
        public final Kind kind;
        public final String category;
        public final String owner; // JVM internal name
        public final String name;  // method name (no descriptor)

        Rule(Kind kind, String category, String owner, String name) {
            this.kind = kind;
            this.category = category;
            this.owner = owner;
            this.name = name;
        }

        /** {@code owner#name} — the human label. */
        public String label() {
            return owner + "#" + name;
        }

        /**
         * Prefix that a matching method-node id starts with, up to the '(' that
         * begins the descriptor — so all overloads match but sibling names do not.
         * e.g. {@code M:java/lang/Runtime#exec(}
         */
        public String targetIdPrefix() {
            return "M:" + owner + "#" + name + "(";
        }
    }

    private final List<Rule> rules;

    private RuleCatalog(List<Rule> rules) {
        this.rules = rules;
    }

    public List<Rule> all() {
        return rules;
    }

    public List<Rule> of(Kind kind) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            if (r.kind == kind) {
                out.add(r);
            }
        }
        return out;
    }

    /** Returns the rule matched by a CALLS target id, or {@code null}. */
    public Rule match(String targetId) {
        if (targetId == null) {
            return null;
        }
        for (Rule r : rules) {
            if (targetId.startsWith(r.targetIdPrefix())) {
                return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ load

    public static RuleCatalog load() {
        String override = System.getProperty("jcgraph.rules");
        if (override != null && !override.trim().isEmpty()) {
            try (InputStream in = Files.newInputStream(Paths.get(override.trim()))) {
                return parse(in);
            } catch (IOException e) {
                System.err.println("[rules] cannot read " + override + " (" + e + "); falling back to built-in");
            }
        }
        try (InputStream in = RuleCatalog.class.getResourceAsStream("/sinks.txt")) {
            if (in == null) {
                System.err.println("[rules] /sinks.txt not found on classpath; catalog is empty");
                return new RuleCatalog(new ArrayList<>());
            }
            return parse(in);
        } catch (IOException e) {
            System.err.println("[rules] cannot read /sinks.txt (" + e + "); catalog is empty");
            return new RuleCatalog(new ArrayList<>());
        }
    }

    private static RuleCatalog parse(InputStream in) throws IOException {
        List<Rule> rules = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.charAt(0) == '#') {
                    continue;
                }
                String[] parts = s.split("\\s+");
                if (parts.length < 3) {
                    continue;
                }
                Kind kind;
                if ("sink".equalsIgnoreCase(parts[0])) {
                    kind = Kind.SINK;
                } else if ("source".equalsIgnoreCase(parts[0])) {
                    kind = Kind.SOURCE;
                } else {
                    continue;
                }
                int hash = parts[2].indexOf('#');
                if (hash <= 0 || hash == parts[2].length() - 1) {
                    continue;
                }
                String owner = parts[2].substring(0, hash);
                String name = parts[2].substring(hash + 1);
                rules.add(new Rule(kind, parts[1], owner, name));
            }
        }
        return new RuleCatalog(rules);
    }
}
