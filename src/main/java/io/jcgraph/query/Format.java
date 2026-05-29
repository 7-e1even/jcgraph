package io.jcgraph.query;

import io.jcgraph.model.Node;

/**
 * Shared, agent-friendly rendering of graph nodes.
 *
 * <p>Every listed symbol leads with its exact node id ({@code M:owner#name(desc)},
 * {@code C:internal}, {@code F:owner#name}) so an agent can paste the token back
 * into the next tool ({@code callers/callees/trace/outline}) without an extra
 * {@code search} round-trip or risking name ambiguity (overloads, same name across
 * classes). The id already encodes owner + name + descriptor, so it doubles as the
 * human label.</p>
 */
public final class Format {

    private Format() {
    }

    /** {@code <kind>  <id>  [file:line]  [entry:KIND]} — the id is directly re-feedable. */
    public static String node(Node n) {
        String entry = n.entryKind != null ? "  [entry:" + n.entryKind + "]" : "";
        return String.format("%-10s %s%s%s", n.kind.tag(), n.id, loc(n), entry);
    }

    /** {@code   [file:line]}, or empty when the node has no recorded location. */
    public static String loc(Node n) {
        if (n == null || n.filePath == null) {
            return "";
        }
        return "   [" + n.filePath + (n.startLine > 0 ? ":" + n.startLine : "") + "]";
    }
}
