package io.jcgraph.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.jcgraph.model.Node;
import io.jcgraph.model.NodeKind;
import io.jcgraph.store.GraphStore;
import io.jcgraph.store.GraphStore.FileRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read-side navigation: search, def, callers, callees, grep. */
public class NavigationService {

    private final GraphStore store;

    public NavigationService(GraphStore store) {
        this.store = store;
    }

    public String search(String query) {
        List<Node> hits = store.findByName(query);
        if (hits.isEmpty()) {
            return "no symbol matching: " + query;
        }
        StringBuilder sb = new StringBuilder();
        for (Node n : hits) {
            sb.append(formatNode(n)).append('\n');
        }
        return sb.toString().trim();
    }

    public String def(String name) {
        List<Node> hits = store.findByName(name);
        if (hits.isEmpty()) {
            return "no definition for: " + name;
        }
        StringBuilder sb = new StringBuilder();
        for (Node n : hits) {
            sb.append(formatNode(n));
            if (n.descriptor != null) {
                sb.append("\n    descriptor: ").append(n.descriptor);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("jcgraph status\n");
        sb.append("  input: ").append(nullToDash(store.getMeta("input"))).append('\n');
        sb.append("  work_dir: ").append(nullToDash(store.getMeta("work_dir"))).append('\n');
        sb.append("  indexed_at: ").append(formatMillis(store.getMeta("indexed_at"))).append('\n');
        sb.append("  nodes: ").append(store.count("nodes"))
                .append("  edges: ").append(store.count("edges"))
                .append("  files: ").append(store.count("files")).append('\n');
        appendGroups(sb, "  files by origin", store.groupFilesBy("origin"));
        appendGroups(sb, "  files by language", store.groupFilesBy("language"));
        String dupCount = store.getMeta("duplicate_classes");
        if (dupCount != null && !dupCount.isEmpty() && !"0".equals(dupCount)) {
            sb.append("  duplicate classes: ").append(dupCount)
                    .append(" (same internal name in multiple jars; only the first was indexed)\n");
            String sample = store.getMeta("duplicate_classes_sample");
            if (sample != null && !sample.isEmpty()) {
                for (String line : sample.split("\n")) {
                    sb.append("    ").append(line).append('\n');
                }
            }
        }
        List<FileRecord> changed = store.changedFiles(20);
        List<String> added = store.addedFiles(20);
        boolean inputChanged = store.inputChangedSinceIndex();
        if (inputChanged || !changed.isEmpty() || !added.isEmpty()) {
            sb.append("  stale: yes");
            if (inputChanged) {
                sb.append(" (input changed since last index)");
            }
            sb.append('\n');
            for (FileRecord f : changed) {
                sb.append("    ").append(f.errors).append(": ").append(f.path).append('\n');
            }
            for (String p : added) {
                sb.append("    added: ").append(p).append('\n');
            }
            if (added.size() == 20) {
                sb.append("    ... more added files (truncated at 20)\n");
            }
            sb.append("  hint: run `jcgraph sync` to rebuild\n");
        } else {
            sb.append("  stale: no\n");
        }
        return sb.toString().trim();
    }

    public String files(String originFilter, String languageFilter, int limit) {
        int cap = limit > 0 ? Math.min(limit, 1000) : 300;
        StringBuilder sb = new StringBuilder();
        sb.append("files");
        if (originFilter != null && !originFilter.isEmpty()) {
            sb.append(" origin=").append(originFilter);
        }
        if (languageFilter != null && !languageFilter.isEmpty()) {
            sb.append(" language=").append(languageFilter);
        }
        sb.append('\n');
        int shown = 0;
        int matched = 0;
        for (FileRecord f : store.files()) {
            if (originFilter != null && !originFilter.isEmpty() && !originFilter.equals(f.origin)) {
                continue;
            }
            if (languageFilter != null && !languageFilter.isEmpty() && !languageFilter.equals(f.language)) {
                continue;
            }
            matched++;
            if (shown >= cap) {
                continue;
            }
            sb.append("  ").append(f.language == null ? "" : f.language)
                    .append("  <").append(f.origin == null ? "" : f.origin).append(">  ")
                    .append(f.path);
            if (f.errors != null && !f.errors.isEmpty()) {
                sb.append("  [").append(f.errors).append(']');
            }
            sb.append('\n');
            shown++;
        }
        if (matched == 0) {
            return "no indexed files matching filters";
        }
        if (matched > shown) {
            sb.append("  ... ").append(matched - shown).append(" more files\n");
        }
        return sb.toString().trim();
    }

    public String node(String nameOrId) {
        List<Node> hits = store.findByName(nameOrId);
        if (hits.isEmpty()) {
            return "no node matching: " + nameOrId;
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Node n : hits) {
            if (shown++ >= 10) {
                sb.append("... refine the query; more matches omitted\n");
                break;
            }
            sb.append(formatNode(n)).append('\n');
            sb.append("    name: ").append(n.name).append('\n');
            if (n.owner != null) {
                sb.append("    owner: ").append(n.owner).append('\n');
            }
            if (n.descriptor != null) {
                sb.append("    descriptor: ").append(n.descriptor).append('\n');
            }
            if (n.filePath != null) {
                sb.append("    location: ").append(n.filePath);
                if (n.startLine > 0) {
                    sb.append(':').append(n.startLine);
                    if (n.endLine > n.startLine) {
                        sb.append('-').append(n.endLine);
                    }
                }
                sb.append('\n');
            }
            if (n.kind != NodeKind.METHOD && n.id.startsWith("C:")) {
                List<Node> methods = store.methodsOf(n.id.substring(2));
                sb.append("    methods: ").append(methods.size()).append('\n');
                int count = 0;
                for (Node m : methods) {
                    if (count++ >= 20) {
                        sb.append("      ... ").append(methods.size() - 20).append(" more\n");
                        break;
                    }
                    sb.append("      ").append(m.id);
                    if (m.startLine > 0) {
                        sb.append(" [").append(m.startLine);
                        if (m.endLine > m.startLine) {
                            sb.append('-').append(m.endLine);
                        }
                        sb.append(']');
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString().trim();
    }

    public String callers(String nameOrId) {
        return callers(nameOrId, Scope.ANY);
    }

    public String callers(String nameOrId, Scope scope) {
        StringBuilder sb = new StringBuilder();
        for (String id : resolveMethodIds(nameOrId)) {
            List<Node> direct = store.callers(id);
            Set<String> directIds = new LinkedHashSet<>();
            for (Node c : direct) {
                directIds.add(c.id);
            }
            // virtual: callers of any ancestor method this one overrides (polymorphic dispatch)
            Set<String> virtual = new LinkedHashSet<>();
            for (String parent : store.overrideParentIds(id)) {
                virtual.addAll(store.callerIds(parent));
            }
            virtual.removeAll(directIds);

            int directKept = 0, virtualKept = 0;
            StringBuilder body = new StringBuilder();
            for (Node c : direct) {
                if (!scope.keep(c)) continue;
                directKept++;
                body.append("    ").append(formatNode(c)).append('\n');
            }
            for (String vid : virtual) {
                if (!scope.keepNodeId(vid, store::getNode)) continue;
                virtualKept++;
                Node n = store.getNode(vid);
                body.append("    ").append(n != null ? formatNode(n) : vid).append("   [virtual]\n");
            }
            sb.append("callers of ").append(id).append("  (").append(directKept)
                    .append(" direct, ").append(virtualKept).append(" via overrides");
            if (!scope.isAny()) {
                sb.append(" [scope=").append(scope).append("] filtered from ")
                        .append(direct.size()).append("/").append(virtual.size());
            }
            sb.append(")\n").append(body);
        }
        return sb.length() == 0 ? "no method matching: " + nameOrId : sb.toString().trim();
    }

    public String callees(String nameOrId) {
        return callees(nameOrId, Scope.ANY);
    }

    public String callees(String nameOrId, Scope scope) {
        StringBuilder sb = new StringBuilder();
        for (String id : resolveMethodIds(nameOrId)) {
            List<String> direct = store.callees(id);
            // virtual: concrete implementations that override each direct (interface/abstract) target
            Set<String> virtual = new LinkedHashSet<>();
            for (String t : direct) {
                virtual.addAll(store.overrideChildIds(t));
            }
            virtual.removeAll(direct);

            int directKept = 0, virtualKept = 0;
            StringBuilder body = new StringBuilder();
            for (String t : direct) {
                if (!scope.keepNodeId(t, store::getNode)) continue;
                directKept++;
                Node n = store.getNode(t);
                body.append("    ").append(n != null ? formatNode(n) : t + "   [external/unresolved]").append('\n');
            }
            for (String t : virtual) {
                if (!scope.keepNodeId(t, store::getNode)) continue;
                virtualKept++;
                Node n = store.getNode(t);
                body.append("    ").append(n != null ? formatNode(n) : t).append("   [virtual impl]\n");
            }
            sb.append("callees of ").append(id).append("  (").append(directKept)
                    .append(" direct, ").append(virtualKept).append(" via overrides");
            if (!scope.isAny()) {
                sb.append(" [scope=").append(scope).append("] filtered from ")
                        .append(direct.size()).append("/").append(virtual.size());
            }
            sb.append(")\n").append(body);
        }
        return sb.length() == 0 ? "no method matching: " + nameOrId : sb.toString().trim();
    }

    public String impact(String nameOrId, int maxDepth) {
        return impact(nameOrId, maxDepth, Scope.ANY);
    }

    public String impact(String nameOrId, int maxDepth, Scope scope) {
        int depthLimit = maxDepth <= 0 ? 2 : Math.min(maxDepth, 8);
        List<String> starts = resolveMethodIds(nameOrId);
        if (starts.isEmpty()) {
            for (Node n : store.findByName(nameOrId)) {
                if (n.id.startsWith("C:")) {
                    for (Node m : store.methodsOf(n.id.substring(2))) {
                        starts.add(m.id);
                    }
                }
            }
        }
        if (starts.isEmpty()) {
            return "no method or class matching: " + nameOrId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("impact of ").append(nameOrId).append(" (reverse callers, depth ")
                .append(depthLimit).append(")");
        if (!scope.isAny()) {
            sb.append("   [scope=").append(scope).append(']');
        }
        sb.append('\n');
        Set<String> seen = new LinkedHashSet<>(starts);
        Deque<String[]> q = new ArrayDeque<>();
        for (String s : starts) {
            q.add(new String[]{s, "0"});
        }
        int shown = 0;
        while (!q.isEmpty() && shown < 200) {
            String[] cur = q.removeFirst();
            String id = cur[0];
            int depth = Integer.parseInt(cur[1]);
            if (depth > 0 && scope.keepNodeId(id, store::getNode)) {
                Node n = store.getNode(id);
                sb.append("  d").append(depth).append("  ")
                        .append(n == null ? id : formatNode(n)).append('\n');
                shown++;
            }
            if (depth >= depthLimit) {
                continue;
            }
            for (String caller : expandedCallerIds(id)) {
                if (seen.add(caller)) {
                    q.addLast(new String[]{caller, String.valueOf(depth + 1)});
                }
            }
        }
        if (shown == 0) {
            sb.append("  no callers found\n");
        } else if (!q.isEmpty()) {
            sb.append("  ... impact output truncated at 200 nodes\n");
        }
        return sb.toString().trim();
    }

    public String context(String task) {
        List<Node> hits = store.findByName(task);
        if (hits.isEmpty()) {
            return "no context found for: " + task;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("context for: ").append(task).append('\n');
        sb.append("entry symbols\n");
        int entries = 0;
        List<Node> methods = new ArrayList<>();
        for (Node n : hits) {
            if (entries++ >= 8) {
                break;
            }
            sb.append("  ").append(formatNode(n)).append('\n');
            if (n.kind == NodeKind.METHOD) {
                methods.add(n);
            } else if (n.id.startsWith("C:")) {
                List<Node> declared = store.methodsOf(n.id.substring(2));
                for (int i = 0; i < declared.size() && methods.size() < 4; i++) {
                    methods.add(declared.get(i));
                }
            }
        }
        if (!methods.isEmpty()) {
            sb.append("call graph summary\n");
            int count = 0;
            for (Node m : methods) {
                if (count++ >= 4) {
                    break;
                }
                List<String> callers = expandedCallerIds(m.id);
                List<String> callees = store.callees(m.id);
                sb.append("  ").append(m.id).append('\n');
                appendIds(sb, "    callers", callers, 5);
                appendIds(sb, "    callees", callees, 5);
            }
        }
        sb.append("next tools: use jcg_node for one symbol, jcg_impact for change risk, ")
                .append("jcg_outline/jcg_method for source confirmation");
        return sb.toString().trim();
    }

    /**
     * Literal grep over every .java on disk. In hybrid mode that includes the
     * decompiled mirror of all bytecode, so this uniformly covers .class bodies too.
     */
    private static final int GREP_FILE_CAP = 300;

    public String grep(String pattern) {
        return grep(pattern, Scope.ANY);
    }

    public String grep(String pattern, Scope scope) {
        StringBuilder sb = new StringBuilder();
        List<String> fileHits = grepJavaFiles(pattern, scope);
        if (!fileHits.isEmpty()) {
            sb.append("== java files (").append(fileHits.size()).append(") ==\n");
            for (String h : fileHits) {
                sb.append("    ").append(h).append('\n');
            }
            if (fileHits.size() >= GREP_FILE_CAP) {
                sb.append("    ... file matches truncated at ").append(GREP_FILE_CAP)
                        .append(" — refine the pattern\n");
            }
        }
        if (sb.length() == 0) {
            return "no match for: " + pattern
                    + (scope.isAny() ? "" : " under scope " + scope);
        }
        return sb.toString().trim();
    }

    private List<String> grepJavaFiles(String pattern, Scope scope) {
        List<String> out = new ArrayList<>();
        String needle = pattern.toLowerCase();
        String pathHint = scope.isAny() ? null : "/" + scope.prefix();
        for (String path : store.javaFiles()) {
            // Cheap path filter for scope: the decompiled mirror writes files at
            // <work>/.sources/<internal>.java, and source inputs are typically
            // under <root>/<pkg-as-path>/, so a substring check on the slashed
            // package prefix is a reliable hit.
            if (pathHint != null && !path.replace('\\', '/').contains(pathHint)) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).toLowerCase().contains(needle)) {
                        out.add(path + ":" + (i + 1) + ":  " + lines.get(i).trim());
                        if (out.size() >= GREP_FILE_CAP) {
                            return out;
                        }
                    }
                }
            } catch (IOException ignored) {
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
            // not-found: return empty so callers report "no method matching" rather than
            // an empty callers/callees list that looks like "method exists but has none".
            return ids;
        }
        for (Node n : store.findByName(nameOrId)) {
            if (n.kind == NodeKind.METHOD) {
                ids.add(n.id);
            }
        }
        return ids;
    }

    private List<String> expandedCallerIds(String methodId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>(store.callerIds(methodId));
        for (String parent : store.overrideParentIds(methodId)) {
            ids.addAll(store.callerIds(parent));
        }
        return new ArrayList<>(ids);
    }

    /**
     * Class skeleton: the class node plus each declared method as a re-feedable
     * {@code M:} id with its line range. Token-cheap way to survey a class before
     * pulling full source with {@code source} / a single method with {@code method}.
     */
    public String outline(String className) {
        String internal = className.replace('.', '/');
        Node cls = store.getNode("C:" + internal);
        List<Node> methods = store.methodsOf(internal);
        if (cls == null && methods.isEmpty()) {
            return "class not found: " + className;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("outline: ").append(cls != null ? Format.node(cls) : "C:" + internal).append('\n');
        sb.append("  ").append(methods.size()).append(" methods\n");
        for (Node m : methods) {
            String range = m.startLine > 0
                    ? "   [" + m.startLine + (m.endLine > m.startLine ? "-" + m.endLine : "") + "]" : "";
            sb.append("    ").append(m.id).append(range).append('\n');
        }
        return sb.toString().trim();
    }

    private static String formatNode(Node n) {
        return Format.node(n);
    }

    private static void appendGroups(StringBuilder sb, String label, List<String[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        sb.append(label).append(':');
        for (String[] r : rows) {
            sb.append(' ').append(r[0].isEmpty() ? "(blank)" : r[0]).append('=').append(r[1]);
        }
        sb.append('\n');
    }

    private static void appendIds(StringBuilder sb, String label, List<String> ids, int limit) {
        sb.append(label).append(" (").append(ids.size()).append(")");
        if (ids.isEmpty()) {
            sb.append('\n');
            return;
        }
        sb.append('\n');
        int shown = 0;
        for (String id : ids) {
            if (shown++ >= limit) {
                sb.append("      ... ").append(ids.size() - limit).append(" more\n");
                break;
            }
            sb.append("      ").append(id).append('\n');
        }
    }

    private static String nullToDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String formatMillis(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        try {
            long millis = Long.parseLong(value);
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(millis));
        } catch (NumberFormatException e) {
            return value;
        }
    }

    // ================================================================ JSON API

    public JsonObject callersJson(String nameOrId, Scope scope) {
        JsonObject root = new JsonObject();
        root.addProperty("tool", "callers");
        root.addProperty("query", nameOrId);
        JsonOut.addScope(root, scope);
        JsonArray targets = new JsonArray();
        for (String id : resolveMethodIds(nameOrId)) {
            JsonObject t = new JsonObject();
            t.addProperty("target", id);
            List<Node> direct = store.callers(id);
            Set<String> directIds = new LinkedHashSet<>();
            for (Node c : direct) {
                directIds.add(c.id);
            }
            Set<String> virtual = new LinkedHashSet<>();
            for (String parent : store.overrideParentIds(id)) {
                virtual.addAll(store.callerIds(parent));
            }
            virtual.removeAll(directIds);
            JsonArray directArr = new JsonArray();
            for (Node c : direct) {
                if (scope.keep(c)) {
                    directArr.add(JsonOut.node(c));
                }
            }
            JsonArray virtualArr = new JsonArray();
            for (String vid : virtual) {
                if (scope.keepNodeId(vid, store::getNode)) {
                    virtualArr.add(JsonOut.node(store.getNode(vid)));
                }
            }
            t.add("direct", directArr);
            t.add("via_overrides", virtualArr);
            targets.add(t);
        }
        root.add("targets", targets);
        return root;
    }

    public JsonObject calleesJson(String nameOrId, Scope scope) {
        JsonObject root = new JsonObject();
        root.addProperty("tool", "callees");
        root.addProperty("query", nameOrId);
        JsonOut.addScope(root, scope);
        JsonArray targets = new JsonArray();
        for (String id : resolveMethodIds(nameOrId)) {
            JsonObject t = new JsonObject();
            t.addProperty("target", id);
            List<String> direct = store.callees(id);
            Set<String> virtual = new LinkedHashSet<>();
            for (String d : direct) {
                virtual.addAll(store.overrideChildIds(d));
            }
            virtual.removeAll(direct);
            JsonArray directArr = new JsonArray();
            for (String d : direct) {
                if (!scope.keepNodeId(d, store::getNode)) continue;
                Node n = store.getNode(d);
                if (n != null) {
                    directArr.add(JsonOut.node(n));
                } else {
                    JsonObject stub = new JsonObject();
                    stub.addProperty("id", d);
                    stub.addProperty("resolved", false);
                    directArr.add(stub);
                }
            }
            JsonArray virtualArr = new JsonArray();
            for (String v : virtual) {
                if (scope.keepNodeId(v, store::getNode)) {
                    virtualArr.add(JsonOut.node(store.getNode(v)));
                }
            }
            t.add("direct", directArr);
            t.add("via_overrides", virtualArr);
            targets.add(t);
        }
        root.add("targets", targets);
        return root;
    }

    public JsonObject impactJson(String nameOrId, int maxDepth, Scope scope) {
        int depthLimit = maxDepth <= 0 ? 2 : Math.min(maxDepth, 8);
        JsonObject root = new JsonObject();
        root.addProperty("tool", "impact");
        root.addProperty("query", nameOrId);
        root.addProperty("depth", depthLimit);
        JsonOut.addScope(root, scope);
        List<String> starts = resolveMethodIds(nameOrId);
        if (starts.isEmpty()) {
            for (Node n : store.findByName(nameOrId)) {
                if (n.id.startsWith("C:")) {
                    for (Node m : store.methodsOf(n.id.substring(2))) {
                        starts.add(m.id);
                    }
                }
            }
        }
        JsonArray callers = new JsonArray();
        if (starts.isEmpty()) {
            root.addProperty("error", "no method or class matching: " + nameOrId);
            root.add("callers", callers);
            return root;
        }
        Set<String> seen = new LinkedHashSet<>(starts);
        Deque<String[]> q = new ArrayDeque<>();
        for (String s : starts) {
            q.add(new String[]{s, "0"});
        }
        while (!q.isEmpty() && callers.size() < 200) {
            String[] cur = q.removeFirst();
            String id = cur[0];
            int depth = Integer.parseInt(cur[1]);
            if (depth > 0 && scope.keepNodeId(id, store::getNode)) {
                JsonObject c = new JsonObject();
                c.addProperty("depth", depth);
                Node n = store.getNode(id);
                if (n != null) {
                    JsonObject nj = JsonOut.node(n);
                    for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : nj.entrySet()) {
                        c.add(e.getKey(), e.getValue());
                    }
                } else {
                    c.addProperty("id", id);
                }
                callers.add(c);
            }
            if (depth >= depthLimit) {
                continue;
            }
            for (String caller : expandedCallerIds(id)) {
                if (seen.add(caller)) {
                    q.addLast(new String[]{caller, String.valueOf(depth + 1)});
                }
            }
        }
        root.add("callers", callers);
        return root;
    }

    public JsonObject grepJson(String pattern, Scope scope) {
        JsonObject root = new JsonObject();
        root.addProperty("tool", "grep");
        root.addProperty("pattern", pattern);
        JsonOut.addScope(root, scope);
        JsonArray files = new JsonArray();
        for (String hit : grepJavaFiles(pattern, scope)) {
            int firstColon = hit.indexOf(':', 3); // skip drive letter colon on Windows
            int secondColon = hit.indexOf(':', firstColon + 1);
            JsonObject f = new JsonObject();
            if (firstColon > 0 && secondColon > firstColon) {
                f.addProperty("path", hit.substring(0, firstColon));
                try {
                    f.addProperty("line", Integer.parseInt(hit.substring(firstColon + 1, secondColon)));
                } catch (NumberFormatException ignored) {
                }
                f.addProperty("text", hit.substring(secondColon + 1).trim());
            } else {
                f.addProperty("raw", hit);
            }
            files.add(f);
        }
        root.add("files", files);
        return root;
    }
}
