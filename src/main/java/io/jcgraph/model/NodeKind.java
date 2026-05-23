package io.jcgraph.model;

/**
 * Kind of a graph node. Stored as a lowercase string in the {@code nodes.kind}
 * column so the schema stays format- and language-agnostic.
 */
public enum NodeKind {
    CLASS,
    INTERFACE,
    ENUM,
    ANNOTATION,
    METHOD,
    FIELD;

    public String tag() {
        return name().toLowerCase();
    }
}
