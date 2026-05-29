package io.jcgraph.model;

/**
 * A graph node: a class/interface/enum/annotation, or a method/field.
 * The id is deterministic (see {@link Ids}) so the bytecode and source
 * frontends converge on the same row for the same logical symbol.
 */
public class Node {
    public String id;
    public NodeKind kind;
    public String name;
    public String owner;       // declaring class internal name, null for top-level class nodes
    public String descriptor;  // JVM descriptor for methods/fields
    /** ASM/JVM access flags; kept in-memory only (CallGraphLinker reads ACC_STATIC/PRIVATE). */
    public transient int access;
    public String filePath;
    public int startLine;
    public int endLine;
    /** Entry classification: HTTP|SERVLET|FILTER|MQ|MAIN|ASYNC, or null for non-entries. */
    public String entryKind;
    /** 1 if ACC_SYNTHETIC or ACC_BRIDGE — bridge methods, compiler accessors, capture fields. */
    public int synthetic;

    public Node() {
    }

    public static Node of(String id, NodeKind kind, String name) {
        Node n = new Node();
        n.id = id;
        n.kind = kind;
        n.name = name;
        return n;
    }
}
