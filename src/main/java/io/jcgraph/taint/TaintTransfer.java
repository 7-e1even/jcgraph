/*
 * Adapted from jar-analyzer (https://github.com/jar-analyzer/jar-analyzer)
 * Original: GPLv3 License, Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 * Ported into jcgraph (package + comments only); remains under GPLv3.
 */
package io.jcgraph.taint;

import java.util.BitSet;

/**
 * Inter-method taint payload, carried hop to hop along a chain.
 *
 * <p>Uses unified locals-index semantics (same as the JVM's first locals
 * segment): for a non-static method index 0 = {@code this}, 1..N = args; for a
 * static method 0..N-1 = args.</p>
 */
public final class TaintTransfer {

    private final BitSet taintedLocals = new BitSet();
    private boolean returnTainted = false;

    public boolean hasTaint() {
        return !taintedLocals.isEmpty() || returnTainted;
    }

    public void markLocal(int localIndex) {
        if (localIndex < 0) {
            return;
        }
        taintedLocals.set(localIndex);
    }

    public boolean isLocalTainted(int localIndex) {
        return localIndex >= 0 && taintedLocals.get(localIndex);
    }

    public BitSet getTaintedLocals() {
        return taintedLocals;
    }

    public boolean isReturnTainted() {
        return returnTainted;
    }

    public void setReturnTainted(boolean returnTainted) {
        this.returnTainted = returnTainted;
    }

    public void reset() {
        taintedLocals.clear();
        returnTainted = false;
    }

    public TaintTransfer copy() {
        TaintTransfer t = new TaintTransfer();
        t.taintedLocals.or(this.taintedLocals);
        t.returnTainted = this.returnTainted;
        return t;
    }

    @Override
    public String toString() {
        return "TaintTransfer{locals=" + taintedLocals + ", retTainted=" + returnTainted + "}";
    }
}
