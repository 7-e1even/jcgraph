package io.jcgraph.query;

import com.google.gson.JsonObject;
import io.jcgraph.model.Node;

/**
 * Tiny shared helpers for the JSON-mode renderers in {@link SecurityService}
 * and {@link NavigationService}. Audit agents lean on JSON output to rank /
 * dedupe / filter across hundreds of sinks; the text renderers are still the
 * default for humans.
 */
public final class JsonOut {

    private JsonOut() {
    }

    /**
     * Compact node descriptor: {@code id/kind/owner/name/origin/file/start_line/end_line}.
     * Omits null/0 fields to keep the response small.
     */
    public static JsonObject node(Node n) {
        JsonObject j = new JsonObject();
        if (n == null) {
            return j;
        }
        j.addProperty("id", n.id);
        if (n.kind != null) {
            j.addProperty("kind", n.kind.tag());
        }
        if (n.owner != null) {
            j.addProperty("owner", n.owner);
        }
        if (n.name != null) {
            j.addProperty("name", n.name);
        }
        if (n.descriptor != null) {
            j.addProperty("descriptor", n.descriptor);
        }
        if (n.filePath != null) {
            j.addProperty("file", n.filePath);
        }
        if (n.startLine > 0) {
            j.addProperty("start_line", n.startLine);
        }
        if (n.endLine > 0) {
            j.addProperty("end_line", n.endLine);
        }
        if (n.entryKind != null) {
            j.addProperty("entry_kind", n.entryKind);
        }
        return j;
    }

    /** Add {@code scope} only when it's a real prefix (omit for {@link Scope#ANY}). */
    public static void addScope(JsonObject obj, Scope scope) {
        if (scope != null && !scope.isAny()) {
            obj.addProperty("scope", scope.prefix());
        }
    }
}
