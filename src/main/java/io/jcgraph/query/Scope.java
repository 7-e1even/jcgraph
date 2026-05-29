package io.jcgraph.query;

import io.jcgraph.model.Node;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Two-dimensional filter for high-volume queries (sinks/sources/taint/callers/...).
 *
 * <p>{@code prefix} is a package internal-name prefix (e.g. {@code com/myorg/})
 * that drops library-jar noise. {@code paths} is a set of file paths (typically
 * produced by {@code git diff --name-only} for a PR review) that further
 * restricts results to changed files. Both default to "match everything".</p>
 *
 * <p>Matching is on the caller's owner / file (the class that *contains* the
 * dangerous call site), not the sink API itself — the sink is always library
 * code; what matters is which code in the project calls it.</p>
 */
public final class Scope {

    public static final Scope ANY = new Scope(null, Collections.<String>emptySet());

    private final String prefix;
    /** Normalized (forward-slash) path suffixes; empty = "any path". */
    private final Set<String> paths;

    private Scope(String prefix, Set<String> paths) {
        this.prefix = prefix;
        this.paths = paths == null ? Collections.<String>emptySet() : paths;
    }

    /**
     * Build a scope from a user-supplied prefix. Accepts both dotted
     * ({@code com.myorg}) and slashed ({@code com/myorg}) forms; blank/null
     * returns {@link #ANY}.
     */
    public static Scope of(String raw) {
        if (raw == null) {
            return ANY;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return ANY;
        }
        return new Scope(s.replace('.', '/'), Collections.<String>emptySet());
    }

    /** Add a set of changed file paths (PR review mode). Null/empty leaves unchanged. */
    public Scope withPaths(Set<String> changedPaths) {
        if (changedPaths == null || changedPaths.isEmpty()) {
            return this;
        }
        Set<String> norm = new LinkedHashSet<>();
        for (String p : changedPaths) {
            if (p == null) continue;
            String t = p.trim().replace('\\', '/');
            if (!t.isEmpty()) {
                norm.add(t);
            }
        }
        return new Scope(this.prefix, norm);
    }

    public boolean isAny() {
        return prefix == null && paths.isEmpty();
    }

    public String prefix() {
        return prefix;
    }

    public boolean hasPaths() {
        return !paths.isEmpty();
    }

    public int pathCount() {
        return paths.size();
    }

    /** Does this absolute file path land in one of the changed-file paths? */
    public boolean keepPath(String filePath) {
        if (paths.isEmpty()) {
            return true;
        }
        if (filePath == null) {
            return false;
        }
        String n = filePath.replace('\\', '/');
        for (String p : paths) {
            if (n.endsWith(p) || n.endsWith("/" + p) || n.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** Does this owner (internal name, slashes) fall under the scope? */
    public boolean keepOwner(String ownerInternal) {
        if (prefix == null) {
            return true;
        }
        return ownerInternal != null && ownerInternal.startsWith(prefix);
    }

    /** Does this jcgraph node id (C:/M:/F:) fall under the scope? */
    public boolean keepId(String id) {
        if (prefix == null) {
            return true;
        }
        if (id == null) {
            return false;
        }
        if (id.startsWith("C:")) {
            return id.startsWith("C:" + prefix);
        }
        if (id.startsWith("M:") || id.startsWith("F:")) {
            return id.startsWith(id.charAt(0) + ":" + prefix);
        }
        return false;
    }

    public boolean keep(Node n) {
        if (n == null) {
            return isAny();
        }
        if (prefix != null) {
            String owner = n.owner != null ? n.owner : (n.id != null && n.id.startsWith("C:")
                    ? n.id.substring(2) : null);
            if (owner == null || !owner.startsWith(prefix)) {
                return false;
            }
        }
        if (!paths.isEmpty() && !keepPath(n.filePath)) {
            return false;
        }
        return true;
    }

    /** Functional callback to resolve an id to its Node — avoids coupling Scope to GraphStore. */
    @FunctionalInterface
    public interface NodeLookup {
        Node get(String id);
    }

    /**
     * Combined check by id: cheap prefix reject first, then if path filtering is
     * active, look the node up (caller provides the lookup) and check its file.
     */
    public boolean keepNodeId(String id, NodeLookup lookup) {
        if (!keepId(id)) {
            return false;
        }
        if (paths.isEmpty()) {
            return true;
        }
        Node n = lookup == null ? null : lookup.get(id);
        return n != null && keepPath(n.filePath);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix == null ? "any" : prefix + "**");
        if (!paths.isEmpty()) {
            sb.append(" + ").append(paths.size()).append(" changed files");
        }
        return sb.toString();
    }
}
