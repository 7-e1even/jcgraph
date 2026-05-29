package io.jcgraph.taint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lightweight step-log collector for a single chain's taint analysis. Replaces
 * jar-analyzer's TaintEvent/TaintEventSink enum machinery with plain text lines,
 * which is all jcgraph's text/MCP output needs.
 *
 * <p>Also tracks the set of sanitizer identifiers that fired on the verified
 * path. Audit agents use this to deprioritize flows where a known cleanser
 * (e.g. parameterized statement, HTML encoder) was observed in the call chain.</p>
 */
public final class TaintSink {
    private final List<String> log = new ArrayList<>();
    private final Set<String> sanitizers = new LinkedHashSet<>();

    public void emit(String message) {
        log.add(message);
    }

    /** Record a sanitizer call site that cleansed taint on this chain. */
    public void recordSanitizer(String identifier) {
        if (identifier != null && !identifier.isEmpty()) {
            sanitizers.add(identifier);
        }
    }

    public List<String> getLog() {
        return log;
    }

    public Set<String> getSanitizers() {
        return sanitizers;
    }
}
