package io.jcgraph.query;

import io.jcgraph.model.Node;
import io.jcgraph.model.NodeKind;
import io.jcgraph.security.RuleCatalog;
import io.jcgraph.store.GraphStore;
import io.jcgraph.taint.TaintResult;
import io.jcgraph.taint.TaintService;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Read-side security analysis: locate dangerous sink call sites and untrusted
 * sources, and trace reverse call chains (taint reachability) to entry points.
 *
 * <p>Built on the polymorphism-resolved call graph: reverse traversal expands
 * through {@code OVERRIDES} edges, so a chain can flow from an interface/abstract
 * call site into the concrete implementation that actually reaches the sink.</p>
 */
public class SecurityService {

    private static final int DEFAULT_MAX_DEPTH = 12;
    private static final int DEFAULT_MAX_PATHS = 20;

    private final GraphStore store;
    private final RuleCatalog catalog;

    public SecurityService(GraphStore store) {
        this.store = store;
        this.catalog = RuleCatalog.load();
    }

    // ----------------------------------------------------------- sink / source

    public String sinks(String categoryFilter) {
        return scan(RuleCatalog.Kind.SINK, categoryFilter, "sinks");
    }

    public String sources(String categoryFilter) {
        return scan(RuleCatalog.Kind.SOURCE, categoryFilter, "sources");
    }

    private String scan(RuleCatalog.Kind kind, String categoryFilter, String label) {
        // category -> "callerLabel  ->  api  [loc]" lines, deduped + sorted
        TreeMap<String, Set<String>> byCategory = new TreeMap<>();
        int total = 0;
        for (RuleCatalog.Rule rule : catalog.of(kind)) {
            if (categoryFilter != null && !categoryFilter.equalsIgnoreCase(rule.category)) {
                continue;
            }
            for (String[] site : store.callSites(rule.targetIdPrefix())) {
                String callerId = site[0];
                Node caller = store.getNode(callerId);
                String line = "    " + (caller != null ? formatNode(caller) : callerId)
                        + "  ->  " + rule.label();
                byCategory.computeIfAbsent(rule.category, k -> new LinkedHashSet<>()).add(line);
                total++;
            }
        }
        if (byCategory.isEmpty()) {
            return "no " + label + " found"
                    + (categoryFilter != null ? " in category: " + categoryFilter : "");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("== ").append(label).append(": ").append(total)
                .append(" call sites across ").append(byCategory.size()).append(" categories ==\n");
        for (java.util.Map.Entry<String, Set<String>> e : byCategory.entrySet()) {
            sb.append("\n[").append(e.getKey()).append("] (").append(e.getValue().size()).append(")\n");
            for (String l : e.getValue()) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------- trace

    public String trace(String nameOrId) {
        return trace(nameOrId, DEFAULT_MAX_DEPTH, DEFAULT_MAX_PATHS);
    }

    /** Reverse call chains from {@code nameOrId} up to entry points / source-reading methods. */
    public String trace(String nameOrId, int maxDepth, int maxPaths) {
        List<String> starts = resolveMethodIds(nameOrId);
        if (starts.isEmpty()) {
            return "no method matching: " + nameOrId;
        }
        Set<String> sourceMethods = methodsReadingSources();

        StringBuilder sb = new StringBuilder();
        for (String start : starts) {
            Node startNode = store.getNode(start);
            sb.append("trace: reverse call chains to ")
                    .append(startNode != null ? formatNode(startNode) : start).append('\n');

            List<List<String>> paths = reversePaths(start, sourceMethods, maxDepth, maxPaths);

            if (paths.isEmpty()) {
                sb.append("    (no callers — this method is itself an entry point)\n");
            }
            int idx = 0;
            for (List<String> path : paths) {
                // path is entry -> ... -> sink (already reversed by reversePaths)
                sb.append("\n  [path ").append(++idx).append("] depth ").append(path.size()).append('\n');
                for (int i = 0; i < path.size(); i++) {
                    String id = path.get(i);
                    Node n = store.getNode(id);
                    String tag = "";
                    if (i == 0) {
                        tag = sourceMethods.contains(id) ? " [source]" : " [entry]";
                    }
                    if (i == path.size() - 1) {
                        tag = tag + " <<< sink";
                    }
                    sb.append(indent(i)).append(i == 0 ? "" : "-> ")
                            .append(n != null ? formatNode(n) : id).append(tag).append('\n');
                }
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------- taint

    /**
     * Flagship vuln command: for every dangerous sink call site, reverse-trace to
     * entry points and taint-verify each candidate chain, reporting only the flows
     * where untrusted input provably reaches the dangerous call.
     */
    public String taint(String categoryFilter) {
        String workDir = store.getMeta("work_dir");
        if (workDir == null) {
            return "no work_dir recorded; re-run `index` (taint needs the extracted .class files)";
        }
        TaintService ts = new TaintService(Paths.get(workDir), RuleCatalog.load());
        Set<String> sourceMethods = methodsReadingSources();

        final int maxDepth = 8, maxPathsPerSink = 3, budget = 600;
        int checked = 0;
        int flows = 0;
        TreeMap<String, Set<String>> byCategory = new TreeMap<>();

        outer:
        for (RuleCatalog.Rule rule : catalog.of(RuleCatalog.Kind.SINK)) {
            if (categoryFilter != null && !categoryFilter.equalsIgnoreCase(rule.category)) {
                continue;
            }
            for (String[] site : store.callSites(rule.targetIdPrefix())) {
                String sinkMethod = site[0];
                String sinkApi = site[1];
                List<List<String>> chains = reversePaths(sinkMethod, sourceMethods, maxDepth, maxPathsPerSink);
                if (chains.isEmpty()) {
                    chains = Collections.singletonList(Collections.singletonList(sinkMethod));
                }
                for (List<String> chain : chains) {
                    if (checked >= budget) {
                        break outer;
                    }
                    checked++;
                    TaintResult r = ts.verify(chain, sinkApi);
                    if (r.pass) {
                        flows++;
                        byCategory.computeIfAbsent(rule.category, k -> new LinkedHashSet<>())
                                .add("    " + chainLabel(chain) + "  ==> " + rule.label());
                    }
                }
            }
        }

        if (byCategory.isEmpty()) {
            return "no taint-verified flows found"
                    + (categoryFilter != null ? " in category: " + categoryFilter : "")
                    + "  (checked " + checked + " chains; note: a FAIL is 'not proven', not 'safe')";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("== taint: ").append(flows).append(" verified flows across ")
                .append(byCategory.size()).append(" categories (checked ").append(checked).append(" chains) ==\n");
        for (Map.Entry<String, Set<String>> e : byCategory.entrySet()) {
            sb.append("\n[").append(e.getKey()).append("] (").append(e.getValue().size()).append(")\n");
            for (String l : e.getValue()) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String chainLabel(List<String> chain) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                sb.append(" -> ");
            }
            Node n = store.getNode(chain.get(i));
            sb.append(n != null && n.owner != null ? n.owner + "#" + n.name : chain.get(i));
        }
        return sb.toString();
    }

    /** Reverse-reachability paths from {@code start}, returned as entry -> ... -> start. */
    private List<List<String>> reversePaths(String start, Set<String> sourceMethods, int maxDepth, int maxPaths) {
        List<List<String>> raw = new ArrayList<>();
        dfs(start, new ArrayList<>(Collections.singletonList(start)),
                new LinkedHashSet<>(Collections.singletonList(start)),
                sourceMethods, maxDepth, maxPaths, raw);
        List<List<String>> out = new ArrayList<>();
        for (List<String> p : raw) {
            List<String> c = new ArrayList<>(p);
            Collections.reverse(c);
            out.add(c);
        }
        return out;
    }

    private void dfs(String cur, List<String> path, LinkedHashSet<String> onPath,
                     Set<String> sourceMethods, int maxDepth, int maxPaths, List<List<String>> out) {
        if (out.size() >= maxPaths) {
            return;
        }
        List<String> callers = callersExpanded(cur);
        boolean entry = callers.isEmpty() || (path.size() > 1 && sourceMethods.contains(cur));
        if (entry || path.size() >= maxDepth) {
            out.add(new ArrayList<>(path));
            return;
        }
        boolean advanced = false;
        for (String c : callers) {
            if (onPath.contains(c)) {
                continue; // cycle guard
            }
            advanced = true;
            path.add(c);
            onPath.add(c);
            dfs(c, path, onPath, sourceMethods, maxDepth, maxPaths, out);
            path.remove(path.size() - 1);
            onPath.remove(c);
            if (out.size() >= maxPaths) {
                return;
            }
        }
        if (!advanced) {
            out.add(new ArrayList<>(path)); // all callers already on this path (cycle) -> leaf
        }
    }

    /** Direct callers plus callers of any ancestor method this one overrides (virtual dispatch). */
    private List<String> callersExpanded(String methodId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>(store.callerIds(methodId));
        for (String parent : store.overrideParentIds(methodId)) {
            ids.addAll(store.callerIds(parent));
        }
        return new ArrayList<>(ids);
    }

    /** Methods that contain a call to any source API (where untrusted input enters). */
    private Set<String> methodsReadingSources() {
        Set<String> out = new LinkedHashSet<>();
        for (RuleCatalog.Rule rule : catalog.of(RuleCatalog.Kind.SOURCE)) {
            for (String[] site : store.callSites(rule.targetIdPrefix())) {
                out.add(site[0]);
            }
        }
        return out;
    }

    private List<String> resolveMethodIds(String nameOrId) {
        List<String> ids = new ArrayList<>();
        if (nameOrId.startsWith("M:")) {
            ids.add(nameOrId);
            return ids;
        }
        for (Node n : store.findByName(nameOrId)) {
            if (n.kind == NodeKind.METHOD) {
                ids.add(n.id);
            }
        }
        return ids;
    }

    private static String indent(int level) {
        StringBuilder sb = new StringBuilder("    ");
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    private static String formatNode(Node n) {
        String loc = n.filePath == null ? "" : "   [" + n.filePath
                + (n.startLine > 0 ? ":" + n.startLine : "") + "]";
        String owner = n.owner != null ? n.owner + "#" : "";
        return owner + n.name + (n.descriptor != null ? n.descriptor : "") + loc;
    }
}
