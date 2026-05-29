package io.jcgraph.taint;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Outcome of taint-verifying one chain: whether taint reached the sink, plus
 * the step log and the set of sanitizers observed on the verified path.
 * A non-empty {@code sanitizersSeen} on a PASS is a strong signal for an
 * auditor to deprioritize the flow (taint was cleansed somewhere upstream).
 */
public final class TaintResult {
    public final boolean pass;
    public final List<String> log;
    public final Set<String> sanitizersSeen;

    public TaintResult(boolean pass, List<String> log) {
        this(pass, log, Collections.<String>emptySet());
    }

    public TaintResult(boolean pass, List<String> log, Set<String> sanitizersSeen) {
        this.pass = pass;
        this.log = log;
        this.sanitizersSeen = sanitizersSeen == null ? Collections.<String>emptySet() : sanitizersSeen;
    }
}
