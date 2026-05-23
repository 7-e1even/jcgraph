package io.jcgraph.taint;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight step-log collector for a single chain's taint analysis. Replaces
 * jar-analyzer's TaintEvent/TaintEventSink enum machinery with plain text lines,
 * which is all jcgraph's text/MCP output needs.
 */
public final class TaintSink {
    private final List<String> log = new ArrayList<>();

    public void emit(String message) {
        log.add(message);
    }

    public List<String> getLog() {
        return log;
    }
}
