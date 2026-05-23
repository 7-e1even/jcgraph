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
    public String signature;
    public int access;
    public String filePath;
    public Origin origin;
    public int startLine;
    public int endLine;

    public Node() {
    }

    public static Node of(String id, NodeKind kind, String name, Origin origin) {
        Node n = new Node();
        n.id = id;
        n.kind = kind;
        n.name = name;
        n.origin = origin;
        return n;
    }
}
