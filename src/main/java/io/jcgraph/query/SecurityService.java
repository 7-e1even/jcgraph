package io.jcgraph.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.jcgraph.model.Node;
import io.jcgraph.model.NodeKind;
import io.jcgraph.security.EntryRules;
import io.jcgraph.security.RuleCatalog;
import io.jcgraph.store.GraphStore;
import io.jcgraph.taint.TaintResult;
import io.jcgraph.taint.TaintService;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    private static final int MAX_SITES = 200;   // cap on sink/source lines shown
    private static final int MAX_FLOWS = 60;     // cap on taint-verified flows shown

    // Taint defaults. Exposed as parameters via taint(category, depth, paths, budget).
    private static final int TAINT_DEFAULT_MAX_DEPTH = 8;
    private static final int TAINT_DEFAULT_MAX_PATHS_PER_SINK = 3;
    private static final int TAINT_DEFAULT_BUDGET = 600;

    private final GraphStore store;
    private final RuleCatalog catalog;

    public SecurityService(GraphStore store) {
        this.store = store;
        this.catalog = RuleCatalog.load();
    }

    // ---------------------------------------------------------------- overview

    /**
     * Cheap triage entry point for an agent: index size plus a per-category
     * histogram of sink and source call sites, so the agent can pick where to
     * aim before dumping anything. Does not run taint (call {@code taint} for
     * verified flows).
     */
    public String overview() {
        return overview(Scope.ANY);
    }

    public String overview(Scope scope) {
        StringBuilder sb = new StringBuilder();
        sb.append("== jcgraph index overview ==\n");
        String input = store.getMeta("input");
        if (input != null) {
            sb.append("input:   ").append(input).append('\n');
        }
        if (!scope.isAny()) {
            sb.append("scope:   ").append(scope).append('\n');
        }
        String at = store.getMeta("indexed_at");
        if (at != null) {
            try {
                sb.append("indexed: ").append(new java.util.Date(Long.parseLong(at))).append('\n');
            } catch (NumberFormatException ignored) {
            }
        }
        int nodes = store.count("nodes");
        int methods = store.countNodesOfKind("method");
        int fields = store.countNodesOfKind("field");
        sb.append("nodes:   ").append(nodes)
                .append("  (classes ").append(Math.max(0, nodes - methods - fields))
                .append(", methods ").append(methods)
                .append(", fields ").append(fields).append(")")
                .append("   edges ").append(store.count("edges")).append('\n');
        String dupCount = store.getMeta("duplicate_classes");
        if (dupCount != null && !dupCount.isEmpty() && !"0".equals(dupCount)) {
            sb.append("dupes:   ").append(dupCount)
                    .append(" duplicate classes shadowed across input jars "
                            + "(run `jcgraph status` for samples)\n");
        }

        sb.append('\n').append(histogram(RuleCatalog.Kind.SINK, "sinks", scope));
        sb.append('\n').append(histogram(RuleCatalog.Kind.SOURCE, "sources", scope));
        sb.append("\nnext: jcg_sinks <category> [--scope <pkg>]   then   jcg_trace <M: id>   "
                + "to find chains from real entries (HTTP/SERVLET) to a sink.\n"
                + "      jcg_taint is best in chain mode: jcg_taint chain=[...] sink_api=M:...  "
                + "after you've triaged a chain with jcg_trace.");
        return sb.toString().trim();
    }

    /** {@code "<label> by category:\n  cat: n\n..."} over categories that have hits. */
    private String histogram(RuleCatalog.Kind kind, String label, Scope scope) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        int total = 0;
        for (RuleCatalog.Rule rule : catalog.of(kind)) {
            int n = 0;
            for (String[] site : store.callSites(rule.targetIdPrefix())) {
                if (!scope.keepId(site[0])) continue;
                Node c = store.getNode(site[0]);
                if (c == null || c.synthetic == 1) continue;
                if (scope.hasPaths() && !scope.keepPath(c.filePath)) continue;
                n++;
            }
            if (n > 0) {
                counts.merge(rule.category, n, Integer::sum);
                total += n;
            }
        }
        if (counts.isEmpty()) {
            return label + ": none\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(" by category (").append(total).append(" call sites):\n");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    // ----------------------------------------------------------- sink / source

    public String sinks(String categoryFilter) {
        return sinks(categoryFilter, Scope.ANY);
    }

    public String sinks(String categoryFilter, Scope scope) {
        return scan(RuleCatalog.Kind.SINK, categoryFilter, "sinks", scope);
    }

    public String sources(String categoryFilter) {
        return sources(categoryFilter, Scope.ANY);
    }

    public String sources(String categoryFilter, Scope scope) {
        return scan(RuleCatalog.Kind.SOURCE, categoryFilter, "sources", scope);
    }

    private String scan(RuleCatalog.Kind kind, String categoryFilter, String label, Scope scope) {
        // category -> "callerLabel  ->  api  [loc]" lines, deduped + sorted
        TreeMap<String, Set<String>> byCategory = new TreeMap<>();
        int total = 0;
        for (RuleCatalog.Rule rule : catalog.of(kind)) {
            if (categoryFilter != null && !categoryFilter.equalsIgnoreCase(rule.category)) {
                continue;
            }
            for (String[] site : store.callSites(rule.targetIdPrefix())) {
                String callerId = site[0];
                if (!scope.keepId(callerId)) {
                    continue;
                }
                Node caller = store.getNode(callerId);
                if (caller != null && caller.synthetic == 1) {
                    continue;
                }
                if (scope.hasPaths() && (caller == null || !scope.keepPath(caller.filePath))) {
                    continue;
                }
                String line = "    " + (caller != null ? formatNode(caller) : callerId)
                        + "  ->  " + rule.label();
                byCategory.computeIfAbsent(rule.category, k -> new LinkedHashSet<>()).add(line);
                total++;
            }
        }
        if (byCategory.isEmpty()) {
            return "no " + label + " found"
                    + (categoryFilter != null ? " in category: " + categoryFilter : "")
                    + (scope.isAny() ? "" : " under scope " + scope);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("== ").append(label).append(": ").append(total)
                .append(" call sites across ").append(byCategory.size()).append(" categories");
        if (!scope.isAny()) {
            sb.append(" [scope=").append(scope).append(']');
        }
        sb.append(" ==\n");
        int shown = 0;
        boolean capped = false;
        for (java.util.Map.Entry<String, Set<String>> e : byCategory.entrySet()) {
            sb.append("\n[").append(e.getKey()).append("] (").append(e.getValue().size()).append(")\n");
            for (String l : e.getValue()) {
                if (shown >= MAX_SITES) {
                    capped = true;
                    break;
                }
                sb.append(l).append('\n');
                shown++;
            }
            if (capped) {
                break;
            }
        }
        if (capped) {
            sb.append("\n... showing ").append(shown).append(" of ").append(total)
                    .append("; narrow with a category filter (")
                    .append(String.join("|", byCategory.keySet())).append(") or --scope <pkg/prefix>\n");
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------- trace

    public String trace(String nameOrId) {
        return trace(nameOrId, DEFAULT_MAX_DEPTH, DEFAULT_MAX_PATHS, Scope.ANY);
    }

    public String trace(String nameOrId, int maxDepth, int maxPaths) {
        return trace(nameOrId, maxDepth, maxPaths, Scope.ANY);
    }

    /** Reverse call chains from {@code nameOrId} up to entry points / source-reading methods. */
    public String trace(String nameOrId, int maxDepth, int maxPaths, Scope scope) {
        List<String> starts = resolveMethodIds(nameOrId);
        if (starts.isEmpty()) {
            return "no method matching: " + nameOrId;
        }
        Set<String> sourceMethods = methodsReadingSources();

        StringBuilder sb = new StringBuilder();
        for (String start : starts) {
            Node startNode = store.getNode(start);
            sb.append("trace: reverse call chains to ")
                    .append(startNode != null ? formatNode(startNode) : start);
            if (!scope.isAny()) {
                sb.append("   [scope=").append(scope).append(']');
            }
            sb.append('\n');

            List<List<String>> raw = reversePaths(start, sourceMethods, maxDepth, maxPaths);
            // Keep paths where at least one hop is in scope; if scope=any, keep all.
            List<List<String>> paths = new ArrayList<>();
            for (List<String> p : raw) {
                if (scope.isAny()) {
                    paths.add(p);
                    continue;
                }
                for (String hop : p) {
                    if (scope.keepNodeId(hop, store::getNode)) {
                        paths.add(p);
                        break;
                    }
                }
            }

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
        return taint(categoryFilter, TAINT_DEFAULT_MAX_DEPTH,
                TAINT_DEFAULT_MAX_PATHS_PER_SINK, TAINT_DEFAULT_BUDGET, Scope.ANY);
    }

    public String taint(String categoryFilter, int maxDepth, int maxPathsPerSink, int budget) {
        return taint(categoryFilter, maxDepth, maxPathsPerSink, budget, Scope.ANY);
    }

    /**
     * Parameterized variant: lets callers widen depth/paths/budget on big projects
     * where defaults would truncate flows, and restrict the scan to a package
     * scope so library-jar noise doesn't drown the project's own sinks.
     */
    public String taint(String categoryFilter, int maxDepth, int maxPathsPerSink, int budget, Scope scope) {
        TaintRun run = runTaint(categoryFilter, maxDepth, maxPathsPerSink, budget, scope);
        if (run.error != null) {
            return run.error;
        }
        StringBuilder header = new StringBuilder();
        header.append(" (checked ").append(run.checked).append(" chains across ")
                .append(run.sinks).append(" sinks");
        if (!scope.isAny()) {
            header.append(" [scope=").append(scope).append(']');
        }
        header.append("; depth=").append(run.maxDepth)
                .append(", paths/sink=").append(run.maxPathsPerSink)
                .append(", budget=").append(run.budget).append(')');
        if (run.budgetExhausted) {
            header.append("  [BUDGET-EXHAUSTED: ").append(run.skipped)
                    .append(" chains skipped -- re-run with larger --budget for full coverage]");
        }

        TreeMap<String, Set<String>> byCategory = new TreeMap<>();
        for (VerifiedFlow f : run.verifiedFlows) {
            byCategory.computeIfAbsent(f.rule.category, k -> new LinkedHashSet<>())
                    .add(renderFlow(f.chain, f.sinkApi, f.rule, f.result));
        }

        if (byCategory.isEmpty()) {
            return "no taint-verified flows found"
                    + (categoryFilter != null ? " in category: " + categoryFilter : "")
                    + header + "  (note: a FAIL is 'not proven', not 'safe')";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("== taint: ").append(run.verifiedFlows.size()).append(" verified flows across ")
                .append(byCategory.size()).append(" categories").append(header).append(" ==\n");
        int shown = 0;
        boolean capped = false;
        for (Map.Entry<String, Set<String>> e : byCategory.entrySet()) {
            sb.append("\n[").append(e.getKey()).append("] (").append(e.getValue().size()).append(")\n");
            for (String l : e.getValue()) {
                if (shown >= MAX_FLOWS) {
                    capped = true;
                    break;
                }
                sb.append(l).append('\n');
                shown++;
            }
            if (capped) {
                break;
            }
        }
        if (capped) {
            sb.append("\n... showing ").append(shown).append(" of ").append(run.verifiedFlows.size())
                    .append(" flows; narrow with a category filter\n");
        }
        if (run.budgetExhausted) {
            sb.append("\nWARNING: ").append(run.skipped)
                    .append(" candidate chains were skipped due to budget; results may be incomplete.\n");
        }
        return sb.toString().trim();
    }

    /** Internal record for a verified taint flow (rule + chain + verification result). */
    private static final class VerifiedFlow {
        final RuleCatalog.Rule rule;
        final List<String> chain;
        final String sinkApi;
        final TaintResult result;

        VerifiedFlow(RuleCatalog.Rule rule, List<String> chain, String sinkApi, TaintResult result) {
            this.rule = rule;
            this.chain = chain;
            this.sinkApi = sinkApi;
            this.result = result;
        }
    }

    /** Aggregate result of one taint run, consumed by both text and JSON renderers. */
    private static final class TaintRun {
        String error;
        int maxDepth;
        int maxPathsPerSink;
        int budget;
        int checked;
        int skipped;
        int sinks;
        boolean budgetExhausted;
        List<VerifiedFlow> verifiedFlows = new ArrayList<>();
    }

    /**
     * Builds the candidate chain list under the budget cap, then verifies each
     * chain in parallel. Verification is CPU-bound (ASM abstract interpretation
     * over each hop's class file) and independent per chain — fans out across
     * cores for a ~Nx speedup on big projects.
     */
    private TaintRun runTaint(String categoryFilter, int maxDepth, int maxPathsPerSink, int budget, Scope scope) {
        TaintRun run = new TaintRun();
        String workDir = store.getMeta("work_dir");
        if (workDir == null) {
            run.error = "no work_dir recorded; re-run `index` (taint needs the extracted .class files)";
            return run;
        }
        if (maxDepth <= 0) maxDepth = TAINT_DEFAULT_MAX_DEPTH;
        if (maxPathsPerSink <= 0) maxPathsPerSink = TAINT_DEFAULT_MAX_PATHS_PER_SINK;
        if (budget <= 0) budget = TAINT_DEFAULT_BUDGET;
        run.maxDepth = maxDepth;
        run.maxPathsPerSink = maxPathsPerSink;
        run.budget = budget;

        TaintService ts = new TaintService(Paths.get(workDir), RuleCatalog.load());
        Set<String> sourceMethods = methodsReadingSources();

        // ---- Phase 1: enumerate work items serially under budget ----
        // Generating chains is cheap (graph walk); the expensive ASM verify happens
        // in phase 2, so the budget is enforced on the verification count.
        List<Object[]> work = new ArrayList<>();
        outer:
        for (RuleCatalog.Rule rule : catalog.of(RuleCatalog.Kind.SINK)) {
            if (categoryFilter != null && !categoryFilter.equalsIgnoreCase(rule.category)) {
                continue;
            }
            for (String[] site : store.callSites(rule.targetIdPrefix())) {
                String sinkMethod = site[0];
                String sinkApi = site[1];
                if (!scope.keepNodeId(sinkMethod, store::getNode)) {
                    continue;
                }
                run.sinks++;
                List<List<String>> chains = new ArrayList<>();
                if (sourceMethods.contains(sinkMethod)) {
                    chains.add(Collections.singletonList(sinkMethod));
                }
                List<List<String>> upstream = reversePaths(sinkMethod, sourceMethods, maxDepth, maxPathsPerSink);
                if (upstream.isEmpty() && chains.isEmpty()) {
                    chains.add(Collections.singletonList(sinkMethod));
                } else {
                    chains.addAll(upstream);
                }
                int remaining = chains.size();
                for (List<String> chain : chains) {
                    if (work.size() >= budget) {
                        run.skipped += remaining;
                        run.budgetExhausted = true;
                        break outer;
                    }
                    work.add(new Object[]{rule, chain, sinkApi});
                    remaining--;
                }
            }
        }
        run.checked = work.size();

        // ---- Phase 2: parallel verification ----
        // TaintService.verify() creates fresh per-call state (TaintSink, TaintTransfer,
        // ClassReader) and the catalog/propagation/sanitizer rules it loads are
        // immutable, so concurrent invocations are safe.
        int threads = Math.max(1, Math.min(work.size(),
                Runtime.getRuntime().availableProcessors()));
        if (threads <= 1 || work.size() <= 1) {
            for (Object[] item : work) {
                @SuppressWarnings("unchecked")
                List<String> chain = (List<String>) item[1];
                TaintResult r = ts.verify(chain, (String) item[2]);
                if (r.pass) {
                    run.verifiedFlows.add(new VerifiedFlow((RuleCatalog.Rule) item[0],
                            chain, (String) item[2], r));
                }
            }
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<VerifiedFlow>> futures = new ArrayList<>(work.size());
            try {
                for (Object[] item : work) {
                    @SuppressWarnings("unchecked")
                    final List<String> chain = (List<String>) item[1];
                    final String sinkApi = (String) item[2];
                    final RuleCatalog.Rule rule = (RuleCatalog.Rule) item[0];
                    futures.add(pool.submit(() -> {
                        TaintResult r = ts.verify(chain, sinkApi);
                        return r.pass ? new VerifiedFlow(rule, chain, sinkApi, r) : null;
                    }));
                }
                for (Future<VerifiedFlow> f : futures) {
                    try {
                        VerifiedFlow vf = f.get();
                        if (vf != null) {
                            run.verifiedFlows.add(vf);
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ExecutionException ee) {
                        // skip this chain; one bad verifier shouldn't drop the run
                    }
                }
            } finally {
                pool.shutdown();
                try {
                    pool.awaitTermination(1, TimeUnit.MINUTES);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return run;
    }

    /**
     * One verified flow as an actionable block: the dangerous API and the
     * compact call chain, then the two ids an agent will actually open — the
     * entry (where untrusted input enters) and the sink-calling method — each
     * with location. If sanitizers fired on the path, list them so an auditor
     * can deprioritize cleansed flows.
     */
    private String renderFlow(List<String> chain, String sinkApi, RuleCatalog.Rule rule, TaintResult r) {
        String entryId = chain.get(0);
        String sinkMethodId = chain.get(chain.size() - 1);
        Node entry = store.getNode(entryId);
        Node sink = store.getNode(sinkMethodId);
        String entryTag = (entry != null && entry.entryKind != null)
                ? "  [entry:" + entry.entryKind + "]" : "";
        StringBuilder sb = new StringBuilder();
        sb.append("    ").append(rule.label()).append("  <==  ").append(chainLabel(chain)).append('\n')
                .append("        entry: ").append(entryId).append(Format.loc(entry)).append(entryTag)
                .append("\n        sink:  ").append(sinkApiShort(sinkApi))
                .append(" @ ").append(sinkMethodId).append(Format.loc(sink));
        if (r != null && !r.sanitizersSeen.isEmpty()) {
            sb.append("\n        sanitizers: ").append(String.join(", ", r.sanitizersSeen));
        }
        return sb.toString();
    }

    /** Drop the {@code M:} prefix from a sink api id for a tighter inline label. */
    private static String sinkApiShort(String sinkApi) {
        return sinkApi != null && sinkApi.startsWith("M:") ? sinkApi.substring(2) : sinkApi;
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
            if (store.getNode(nameOrId) != null) {
                ids.add(nameOrId);
            }
            // not-found: return empty so callers surface "no method matching" rather than
            // silently producing a 0-callers / empty trace that looks like "no calls".
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
        return Format.node(n);
    }

    // ================================================================ JSON API

    public JsonObject sinksJson(String categoryFilter, Scope scope) {
        return scanJson(RuleCatalog.Kind.SINK, categoryFilter, "sinks", scope);
    }

    public JsonObject sourcesJson(String categoryFilter, Scope scope) {
        return scanJson(RuleCatalog.Kind.SOURCE, categoryFilter, "sources", scope);
    }

    private JsonObject scanJson(RuleCatalog.Kind kind, String categoryFilter, String label, Scope scope) {
        JsonObject root = new JsonObject();
        root.addProperty("tool", label);
        if (categoryFilter != null) {
            root.addProperty("category_filter", categoryFilter);
        }
        JsonOut.addScope(root, scope);
        JsonArray sites = new JsonArray();
        int total = 0;
        for (RuleCatalog.Rule rule : catalog.of(kind)) {
            if (categoryFilter != null && !categoryFilter.equalsIgnoreCase(rule.category)) {
                continue;
            }
            for (String[] site : store.callSites(rule.targetIdPrefix())) {
                String callerId = site[0];
                if (!scope.keepId(callerId)) {
                    continue;
                }
                Node caller = store.getNode(callerId);
                if (caller != null && caller.synthetic == 1) {
                    continue;
                }
                if (scope.hasPaths() && (caller == null || !scope.keepPath(caller.filePath))) {
                    continue;
                }
                JsonObject s = new JsonObject();
                s.addProperty("category", rule.category);
                s.addProperty("rule", rule.label());
                s.addProperty("api", site[1]);
                s.add("caller", JsonOut.node(caller != null ? caller
                        : stubMethodNode(callerId)));
                sites.add(s);
                total++;
            }
        }
        root.addProperty("count", total);
        root.add("sites", sites);
        return root;
    }

    public JsonObject taintJson(String categoryFilter, int maxDepth, int maxPathsPerSink,
                                int budget, Scope scope) {
        JsonObject root = new JsonObject();
        root.addProperty("tool", "taint");
        if (categoryFilter != null) {
            root.addProperty("category_filter", categoryFilter);
        }
        JsonOut.addScope(root, scope);
        TaintRun run = runTaint(categoryFilter, maxDepth, maxPathsPerSink, budget, scope);
        if (run.error != null) {
            root.addProperty("error", run.error);
            return root;
        }
        JsonObject params = new JsonObject();
        params.addProperty("depth", run.maxDepth);
        params.addProperty("paths_per_sink", run.maxPathsPerSink);
        params.addProperty("budget", run.budget);
        root.add("params", params);

        List<JsonObject> flowList = new ArrayList<>();
        for (VerifiedFlow f : run.verifiedFlows) {
            flowList.add(renderFlowJson(f.chain, f.sinkApi, f.rule, f.result));
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("flows", flowList.size());
        summary.addProperty("checked", run.checked);
        summary.addProperty("sinks_scanned", run.sinks);
        summary.addProperty("budget_exhausted", run.budgetExhausted);
        if (run.budgetExhausted) {
            summary.addProperty("skipped", run.skipped);
        }
        root.add("summary", summary);
        // Rank flows so real attack surfaces (HTTP/SERVLET/FILTER/MQ) sort first;
        // flows with sanitizer hits drop a notch (auditor likely deprioritizes).
        flowList.sort((a, b) -> Integer.compare(priorityOf(a), priorityOf(b)));
        JsonArray flowsArr = new JsonArray();
        for (JsonObject f : flowList) {
            flowsArr.add(f);
        }
        root.add("flows", flowsArr);
        return root;
    }

    private static int priorityOf(JsonObject flow) {
        JsonObject entry = flow.has("entry") && flow.get("entry").isJsonObject()
                ? flow.getAsJsonObject("entry") : null;
        String kind = entry != null && entry.has("entry_kind")
                ? entry.get("entry_kind").getAsString() : null;
        int base = EntryRules.priority(kind);
        if (flow.has("sanitizers")) {
            base += 10; // observed sanitizer -> deprioritize, but still rank within bucket
        }
        return base;
    }

    private JsonObject renderFlowJson(List<String> chain, String sinkApi,
                                      RuleCatalog.Rule rule, TaintResult r) {
        JsonObject f = new JsonObject();
        f.addProperty("category", rule.category);
        f.addProperty("rule", rule.label());
        f.addProperty("sink_api", sinkApi);
        JsonArray chainArr = new JsonArray();
        for (String id : chain) {
            chainArr.add(id);
        }
        f.add("chain", chainArr);
        String entryId = chain.get(0);
        String sinkMethodId = chain.get(chain.size() - 1);
        f.add("entry", JsonOut.node(node(entryId)));
        f.add("sink_method", JsonOut.node(node(sinkMethodId)));
        // Sanitizers seen on the verified path -- empty if none fired.
        if (r != null && r.sanitizersSeen != null && !r.sanitizersSeen.isEmpty()) {
            JsonArray s = new JsonArray();
            for (String name : r.sanitizersSeen) {
                s.add(name);
            }
            f.add("sanitizers", s);
        }
        return f;
    }

    private Node node(String id) {
        Node n = store.getNode(id);
        return n != null ? n : stubMethodNode(id);
    }

    // ================================================== chain-mode verification

    /**
     * Verify a single user-supplied chain. Use this when you (or an agent) have
     * already triaged a suspicious {@code jcg_trace} path and want to confirm
     * data-flow on exactly that chain — the global {@code taint} scanner
     * underperforms on heap-heavy code (Spring/DI/BI), so chain-mode is the
     * intended way to get high-signal PASS evidence on a specific path.
     */
    public String verifyChain(List<String> chain, String sinkApi) {
        TaintResult r = runChain(chain, sinkApi);
        if (r == null) {
            return "no work_dir recorded; re-run `index` (taint needs the extracted .class files)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("== verify-chain: ").append(r.pass ? "PASS" : "FAIL").append(" ==\n");
        sb.append("chain: ");
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) sb.append(" -> ");
            sb.append(chain.get(i));
        }
        sb.append('\n');
        sb.append("sink_api: ").append(sinkApi).append('\n');
        if (!r.sanitizersSeen.isEmpty()) {
            sb.append("sanitizers observed: ").append(String.join(", ", r.sanitizersSeen)).append('\n');
        }
        sb.append("\nstep log:\n");
        for (String line : r.log) {
            sb.append("  ").append(line).append('\n');
        }
        if (!r.pass) {
            sb.append("\nFAIL means 'taint could not be proven to reach the sink on this chain'");
            sb.append("\n(could be a real safe path, OR an analysis blind spot — heap/field, branches, async).");
        }
        return sb.toString().trim();
    }

    /** JSON variant for agent chaining. */
    public JsonObject verifyChainJson(List<String> chain, String sinkApi) {
        JsonObject root = new JsonObject();
        root.addProperty("tool", "taint-chain");
        TaintResult r = runChain(chain, sinkApi);
        if (r == null) {
            root.addProperty("error", "no work_dir recorded; re-run `index`");
            return root;
        }
        root.addProperty("pass", r.pass);
        JsonArray chainArr = new JsonArray();
        for (String id : chain) chainArr.add(id);
        root.add("chain", chainArr);
        root.addProperty("sink_api", sinkApi);
        if (!r.sanitizersSeen.isEmpty()) {
            JsonArray s = new JsonArray();
            for (String name : r.sanitizersSeen) s.add(name);
            root.add("sanitizers", s);
        }
        JsonArray log = new JsonArray();
        for (String line : r.log) log.add(line);
        root.add("log", log);
        return root;
    }

    private TaintResult runChain(List<String> chain, String sinkApi) {
        String workDir = store.getMeta("work_dir");
        if (workDir == null) {
            return null;
        }
        TaintService ts = new TaintService(Paths.get(workDir), RuleCatalog.load());
        return ts.verify(chain, sinkApi);
    }

    private static Node stubMethodNode(String id) {
        Node n = new Node();
        n.id = id;
        return n;
    }
}
