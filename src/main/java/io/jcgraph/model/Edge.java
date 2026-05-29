package io.jcgraph.model;

/** A directed relationship between two node ids. */
public class Edge {
    public String source;
    public String target;
    public EdgeKind kind;
    public int line;
    public Origin origin;
    public String provenance;

    public Edge() {
    }

    public static Edge of(String source, String target, EdgeKind kind, Origin origin, String provenance) {
        Edge e = new Edge();
        e.source = source;
        e.target = target;
        e.kind = kind;
        e.origin = origin;
        e.provenance = provenance;
        return e;
    }

    /** Source-side call/reference edge with a line number. */
    public static Edge call(String source, String target, int line, String provenance, EdgeKind kind) {
        Edge e = new Edge();
        e.source = source;
        e.target = target;
        e.kind = kind;
        e.line = line;
        e.origin = Origin.SOURCE;
        e.provenance = provenance;
        return e;
    }
}
