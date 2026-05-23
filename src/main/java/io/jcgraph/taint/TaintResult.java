package io.jcgraph.taint;

import java.util.List;

/** Outcome of taint-verifying one chain: whether taint reached the sink, plus the step log. */
public final class TaintResult {
    public final boolean pass;
    public final List<String> log;

    public TaintResult(boolean pass, List<String> log) {
        this.pass = pass;
        this.log = log;
    }
}
