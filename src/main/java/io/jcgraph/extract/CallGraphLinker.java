package io.jcgraph.extract;

import io.jcgraph.model.Edge;
import io.jcgraph.model.EdgeKind;
import io.jcgraph.model.Ids;
import io.jcgraph.model.Node;
import io.jcgraph.model.NodeKind;
import io.jcgraph.model.Origin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Global linking pass that runs after per-class extraction: it resolves
 * polymorphism by emitting {@link EdgeKind#OVERRIDES} edges
 * ({@code subMethod -> superMethod}) wherever a method has the same name+descriptor
 * as one declared by an ancestor type.
 *
 * <p>The raw bytecode call graph records the <em>declared</em> type at each call
 * site, so a call through an interface (e.g. {@code Filter#doFilter}) never reaches
 * the concrete implementations. OVERRIDES edges are the bridge: query-time call
 * graph expansion (see {@code GraphStore.calleesExpanded/callersExpanded}) walks
 * them so taint can flow into implementations — essential for vulnerability hunting.</p>
 */
public final class CallGraphLinker {

    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_PRIVATE = 0x0002;

    /**
     * Computes OVERRIDES edges from the extracted nodes/edges. Only links within
     * indexed code (an ancestor with no method node of its own contributes nothing).
     */
    public List<Edge> link(List<Node> nodes, List<Edge> edges) {
        // type -> direct supertypes (superclass + interfaces), from EXTENDS/IMPLEMENTS edges
        Map<String, List<String>> supertypes = new HashMap<>();
        for (Edge e : edges) {
            if (e.kind == EdgeKind.EXTENDS || e.kind == EdgeKind.IMPLEMENTS) {
                String sub = internal(e.source);
                String sup = internal(e.target);
                if (sub != null && sup != null) {
                    supertypes.computeIfAbsent(sub, k -> new ArrayList<>()).add(sup);
                }
            }
        }

        // owner internal -> set of "name+descriptor" it declares
        Map<String, Set<String>> methodsByOwner = new HashMap<>();
        for (Node n : nodes) {
            if (n.kind == NodeKind.METHOD && n.owner != null && n.descriptor != null) {
                methodsByOwner.computeIfAbsent(n.owner, k -> new HashSet<>())
                        .add(n.name + n.descriptor);
            }
        }

        List<Edge> overrides = new ArrayList<>();
        for (Node n : nodes) {
            if (n.kind != NodeKind.METHOD || n.owner == null || n.descriptor == null) {
                continue;
            }
            if ("<init>".equals(n.name) || "<clinit>".equals(n.name)) {
                continue; // constructors/static-init are not overridable
            }
            if ((n.access & ACC_STATIC) != 0 || (n.access & ACC_PRIVATE) != 0) {
                continue; // static/private dispatch is not virtual
            }
            String sigKey = n.name + n.descriptor;
            for (String ancestor : ancestors(n.owner, supertypes)) {
                Set<String> sigs = methodsByOwner.get(ancestor);
                if (sigs != null && sigs.contains(sigKey)) {
                    overrides.add(Edge.of(n.id, Ids.method(ancestor, n.name, n.descriptor),
                            EdgeKind.OVERRIDES, Origin.BYTECODE, "hierarchy"));
                }
            }
        }
        return overrides;
    }

    /** All transitive supertypes of {@code type} (excluding itself). */
    private static Set<String> ancestors(String type, Map<String, List<String>> supertypes) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        List<String> direct = supertypes.get(type);
        if (direct != null) {
            queue.addAll(direct);
        }
        while (!queue.isEmpty()) {
            String t = queue.poll();
            if (!seen.add(t)) {
                continue;
            }
            List<String> next = supertypes.get(t);
            if (next != null) {
                queue.addAll(next);
            }
        }
        return seen;
    }

    /** {@code C:com/x/Y -> com/x/Y}; returns null for non-class ids. */
    private static String internal(String classId) {
        return classId != null && classId.startsWith("C:") ? classId.substring(2) : null;
    }
}
