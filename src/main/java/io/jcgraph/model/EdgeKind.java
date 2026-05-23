package io.jcgraph.model;

/**
 * Kind of a relationship between two nodes. Stored lowercase in
 * {@code edges.kind}.
 */
public enum EdgeKind {
    CONTAINS,    // class -> method/field
    CALLS,       // method -> method
    EXTENDS,     // class -> superclass
    IMPLEMENTS,  // class -> interface
    OVERRIDES,   // method -> method
    REFERENCES;  // best-effort / unresolved

    public String tag() {
        return name().toLowerCase();
    }
}
