package io.jcgraph.model;

/**
 * Which frontend produced a node/edge. Bytecode (ASM over .class) gives precise
 * descriptors and call edges; source (JavaParser over .java) gives exact
 * positions and readable bodies.
 */
public enum Origin {
    BYTECODE,
    SOURCE;

    public String tag() {
        return name().toLowerCase();
    }
}
