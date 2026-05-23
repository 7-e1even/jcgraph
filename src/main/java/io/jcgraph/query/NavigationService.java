package io.jcgraph.query;

import io.jcgraph.model.Descriptors;
import io.jcgraph.model.Node;
import io.jcgraph.model.NodeKind;
import io.jcgraph.store.GraphStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
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
            if (n.signature != null) {
                sb.append("\n    signature:  ").append(n.signature);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public String callers(String nameOrId) {
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

            sb.append("callers of ").append(id).append("  (").append(direct.size())
                    .append(" direct, ").append(virtual.size()).append(" via overrides)\n");
            for (Node c : direct) {
                sb.append("    ").append(formatNode(c)).append('\n');
            }
            for (String vid : virtual) {
                Node n = store.getNode(vid);
                sb.append("    ").append(n != null ? formatNode(n) : vid).append("   [virtual]\n");
            }
        }
        return sb.length() == 0 ? "no method matching: " + nameOrId : sb.toString().trim();
    }

    public String callees(String nameOrId) {
        StringBuilder sb = new StringBuilder();
        for (String id : resolveMethodIds(nameOrId)) {
            List<String> direct = store.callees(id);
            // virtual: concrete implementations that override each direct (interface/abstract) target
            Set<String> virtual = new LinkedHashSet<>();
            for (String t : direct) {
                virtual.addAll(store.overrideChildIds(t));
            }
            virtual.removeAll(direct);

            sb.append("callees of ").append(id).append("  (").append(direct.size())
                    .append(" direct, ").append(virtual.size()).append(" via overrides)\n");
            for (String t : direct) {
                Node n = store.getNode(t);
                sb.append("    ").append(n != null ? formatNode(n) : t + "   [external/unresolved]").append('\n');
            }
            for (String t : virtual) {
                Node n = store.getNode(t);
                sb.append("    ").append(n != null ? formatNode(n) : t).append("   [virtual impl]\n");
            }
        }
        return sb.length() == 0 ? "no method matching: " + nameOrId : sb.toString().trim();
    }

    /**
     * Literal grep: string constants (constant pool, instant) plus a text scan of
     * every .java on disk. In hybrid mode that includes the decompiled mirror of all
     * bytecode, so this uniformly covers .class bodies too — no special mode needed.
     */
    public String grep(String pattern) {
        StringBuilder sb = new StringBuilder();
        List<String[]> lits = store.grepStrings(pattern);
        if (!lits.isEmpty()) {
            sb.append("== string constants (").append(lits.size()).append(") ==\n");
            for (String[] s : lits) {
                sb.append("    ").append(s[0]).append('#').append(s[1] == null ? "" : s[1])
                        .append("   \"").append(s[2]).append("\"\n");
            }
        }
        List<String> fileHits = grepJavaFiles(pattern);
        if (!fileHits.isEmpty()) {
            sb.append("== java files (").append(fileHits.size()).append(") ==\n");
            for (String h : fileHits) {
                sb.append("    ").append(h).append('\n');
            }
        }
        if (sb.length() == 0) {
            return "no match for: " + pattern;
        }
        return sb.toString().trim();
    }

    private List<String> grepJavaFiles(String pattern) {
        List<String> out = new ArrayList<>();
        String needle = pattern.toLowerCase();
        for (String path : store.javaFiles()) {
            try {
                List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).toLowerCase().contains(needle)) {
                        out.add(path + ":" + (i + 1) + ":  " + lines.get(i).trim());
                        if (out.size() >= 500) {
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

    private static String formatNode(Node n) {
        String loc = n.filePath == null ? "" : "   [" + n.filePath
                + (n.startLine > 0 ? ":" + n.startLine : "") + "]";
        String params = n.kind == NodeKind.METHOD && n.descriptor != null
                ? "(" + Descriptors.paramCount(n.descriptor) + " params)" : "";
        String owner = n.owner != null ? n.owner + "#" : "";
        return String.format("%-10s %s%s %s   <%s>%s",
                n.kind.tag(), owner, n.name, params, n.origin.tag(), loc);
    }
}
