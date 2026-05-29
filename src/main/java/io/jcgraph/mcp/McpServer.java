package io.jcgraph.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.jcgraph.content.ContentResolver;
import io.jcgraph.model.Ids;
import io.jcgraph.model.Node;
import io.jcgraph.query.NavigationService;
import io.jcgraph.query.Scope;
import io.jcgraph.query.SecurityService;
import io.jcgraph.store.GraphStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Minimal MCP server over stdio (newline-delimited JSON-RPC 2.0).
 *
 * <p>Exposes the jcgraph read API as MCP tools so an agent (e.g. Tamamo) can
 * navigate a pre-built index. Reads requests on stdin, writes responses on
 * stdout; all diagnostics go to stderr so the protocol channel stays clean.</p>
 *
 * <p>Run: {@code java -jar jcgraph.jar serve --mcp --db <path>}</p>
 */
public class McpServer {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final int MAX_OUTPUT_CHARS = 15000;

    private final GraphStore store;
    private final NavigationService nav;
    private final ContentResolver content;
    private final SecurityService security;

    public McpServer(String dbPath) {
        this.store = GraphStore.open(dbPath);
        this.nav = new NavigationService(store);
        this.content = new ContentResolver(store);
        this.security = new SecurityService(store);
    }

    public void run() {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        System.err.println("[jcgraph-mcp] ready (nodes=" + store.count("nodes") + ")");
        try {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                JsonObject req;
                try {
                    req = JsonParser.parseString(line).getAsJsonObject();
                } catch (Exception e) {
                    continue; // ignore non-JSON noise
                }
                handle(req, out);
            }
        } catch (IOException e) {
            System.err.println("[jcgraph-mcp] stdin closed: " + e);
        } finally {
            store.close();
        }
    }

    private void handle(JsonObject req, PrintWriter out) {
        JsonElement id = req.get("id");
        boolean isNotification = (id == null);
        String method = req.has("method") ? req.get("method").getAsString() : "";
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        try {
            JsonObject result = dispatch(method, params);
            if (!isNotification && result != null) {
                writeResult(out, id, result);
            }
        } catch (RpcError re) {
            if (!isNotification) {
                writeError(out, id, re.code, re.getMessage());
            }
        } catch (Exception e) {
            if (!isNotification) {
                writeError(out, id, -32603, "internal error: " + e);
            }
            System.err.println("[jcgraph-mcp] error on " + method + ": " + e);
        }
    }

    private JsonObject dispatch(String method, JsonObject params) {
        switch (method) {
            case "initialize":
                return initializeResult();
            case "notifications/initialized":
            case "notifications/cancelled":
                return null; // notifications: no response
            case "ping":
                return new JsonObject();
            case "tools/list":
                return toolsList();
            case "tools/call":
                return toolsCall(params);
            default:
                throw new RpcError(-32601, "method not found: " + method);
        }
    }

    // -------------------------------------------------------------- handlers

    private JsonObject initializeResult() {
        JsonObject r = new JsonObject();
        r.addProperty("protocolVersion", PROTOCOL_VERSION);
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        r.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "jcgraph");
        info.addProperty("version", "0.1.0");
        r.add("serverInfo", info);
        r.addProperty("instructions",
                "Java code graph over jar/war/class/jmod/java. All tools are prefixed jcg_ and operate ONLY on "
                        + "the indexed Java project (not the local filesystem). "
                        + "EVERY result line leads with the exact node id (M:owner#name(desc) / C:internal / "
                        + "F:owner#name) — paste that id straight into the next tool; no need to re-search, and it "
                        + "avoids name ambiguity from overloads. "
                        + "Recommended vuln-hunting flow:\n"
                        + "  (1) jcg_overview [scope=pkg/prefix] — attack surface + entry-point classification\n"
                        + "  (2) jcg_sinks <category> [scope=...] — narrow to a sink class you care about\n"
                        + "  (3) jcg_trace <M:sink-method> — find reverse-call chains; each path's root is tagged "
                        + "[entry:HTTP|SERVLET|FILTER|MQ|MAIN|ASYNC] when it's a real attack-surface entry\n"
                        + "  (4) for a suspicious chain, OPTIONAL: jcg_taint chain=[M:a,M:b,...] sink_api=M:... "
                        + "to bytecode-verify data-flow. PASS = high-signal evidence. The scan mode of jcg_taint "
                        + "(no chain arg) tends to return 0 flows on Spring/DI/BI code because it has no heap "
                        + "model — prefer chain mode for high-value evidence.\n"
                        + "  (5) jcg_outline then jcg_method to read the bodies and decide.\n"
                        + "Navigation: jcg_search/jcg_def locate symbols, jcg_callers/jcg_callees walk the call "
                        + "graph (expanded through overrides so interface/abstract calls reach implementations), "
                        + "jcg_grep searches the indexed Java text. format='json' on high-volume tools returns "
                        + "structured results agents can chain. Trust results; no need to re-verify with grep.");
        return r;
    }

    private JsonObject toolsList() {
        JsonArray tools = new JsonArray();
        // Tools that accept --scope / format=json share these param descriptions.
        final String scopeDesc = "package prefix (com/myorg/ or com.myorg) to drop library-jar noise";
        final String jsonDesc = "set 'json' for a structured response agents can chain (default: text)";
        JsonObject overviewTool = toolDef("jcg_overview", "START HERE. Index size plus a per-category histogram of sink "
                + "and source call sites, so you can pick where to aim before dumping anything. "
                + "Pass --scope to focus the histogram on a package prefix.");
        overviewTool.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("scope", strProp(scopeDesc));
        tools.add(overviewTool);
        tools.add(toolDef("jcg_status", "Index status: input, work dir, counts, file groups, and stale files. No args."));
        JsonObject filesTool = toolDef("jcg_files", "List indexed files from the jcgraph database. Optional origin/language filters and limit.");
        JsonObject filesProps = filesTool.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        filesProps.add("origin", strProp("optional origin filter: bytecode|source|decompiled"));
        filesProps.add("language", strProp("optional language filter: class|java"));
        filesProps.add("limit", intProp("maximum files to show (default 300, cap 1000)"));
        tools.add(filesTool);
        tools.add(tool("jcg_context", "PRIMARY TOOL for Java/JVM questions. Builds compact context from search hits plus caller/callee summaries in one call.",
                "task", "task, feature, symbol, or bug description"));
        tools.add(tool("jcg_search", "Find Java symbols (class/method/field) by name in the jcgraph index. "
                        + "Each result line leads with the exact node id (M:/C:/F:) — paste it into the next tool.",
                "query", "symbol name or substring"));
        tools.add(tool("jcg_def", "Show a Java symbol's definition (kind, owner, location, descriptor, "
                        + "signature) from the jcgraph index.",
                "name", "symbol name"));
        tools.add(tool("jcg_node", "Get exact node details for one id/name. Classes return a compact method outline instead of full source.",
                "symbol", "exact node id or symbol query"));
        JsonObject callersTool = tool("jcg_callers",
                "List methods that call the given method (jcgraph call graph).",
                "method", "method name or full id (M:owner#name<desc>)");
        addCommonOptionalProps(callersTool, scopeDesc, jsonDesc);
        tools.add(callersTool);
        JsonObject calleesTool = tool("jcg_callees",
                "List methods called by the given method (jcgraph call graph).",
                "method", "method name or full id (M:owner#name<desc>)");
        addCommonOptionalProps(calleesTool, scopeDesc, jsonDesc);
        tools.add(calleesTool);
        JsonObject impactTool = toolDef("jcg_impact", "Analyze reverse caller impact radius for a method or class before changing it.");
        JsonObject impactProps = impactTool.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        impactProps.add("symbol", strProp("method/class name or exact node id"));
        impactProps.add("depth", intProp("caller depth (default 2, cap 8)"));
        JsonArray impactReq = new JsonArray();
        impactReq.add("symbol");
        impactTool.getAsJsonObject("inputSchema").add("required", impactReq);
        addCommonOptionalProps(impactTool, scopeDesc, jsonDesc);
        tools.add(impactTool);
        JsonObject grepTool = tool("jcg_grep", "Search the INDEXED Java code in the jcgraph database (string constants "
                        + "+ decompiled/native .java). NOT a filesystem grep — it only searches the indexed Java project.",
                "pattern", "text to search for");
        addCommonOptionalProps(grepTool, scopeDesc, jsonDesc);
        tools.add(grepTool);
        tools.add(tool("jcg_outline", "Class skeleton: each declared method as a re-feedable M: id with its "
                        + "line range. Token-cheap survey of a class before pulling full source.",
                "className", "fully-qualified class name, e.g. com.example.Foo"));
        tools.add(tool("jcg_source", "Print a Java class's source from the jcgraph index "
                        + "(decompiled if it came from bytecode). Large classes can be big — prefer jcg_outline "
                        + "then jcg_method for a single method.",
                "className", "fully-qualified class name, e.g. com.example.Foo"));
        JsonObject methodTool = toolDef("jcg_method",
                "Print a single Java method's source from the jcgraph index (decompiled if bytecode).");
        JsonObject props = methodTool.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        props.add("className", strProp("fully-qualified class name"));
        props.add("methodName", strProp("method name"));
        JsonArray req = new JsonArray();
        req.add("className");
        req.add("methodName");
        methodTool.getAsJsonObject("inputSchema").add("required", req);
        tools.add(methodTool);

        // security tools (vulnerability hunting)
        JsonObject sinksTool = toolDef("jcg_sinks",
                "List dangerous SINK call sites in the indexed project (OS exec, SQL, deserialization, "
                        + "reflection, JNDI, file, SSRF, XXE, expression). Optional category filter.");
        sinksTool.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("category",
                strProp("optional category: exec|sql|deserialize|reflect|jndi|file|ssrf|xxe|expr|redirect"));
        addCommonOptionalProps(sinksTool, scopeDesc, jsonDesc);
        tools.add(sinksTool);

        JsonObject sourcesTool = toolDef("jcg_sources",
                "List untrusted-input SOURCE read sites (e.g. HttpServletRequest#getParameter/getHeader). "
                        + "Optional category filter.");
        sourcesTool.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("category",
                strProp("optional category: http|net"));
        addCommonOptionalProps(sourcesTool, scopeDesc, jsonDesc);
        tools.add(sourcesTool);

        JsonObject traceTool = tool("jcg_trace",
                "Reverse call chains (call-reachability) from a method up to entry points / "
                        + "source-reading methods. Expands through OVERRIDES so chains flow through "
                        + "interface/abstract calls into implementations. Use on a sink-calling method.",
                "method", "method name or full id (M:owner#name<desc>)");
        traceTool.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("scope", strProp(scopeDesc));
        tools.add(traceTool);

        JsonObject taintTool = toolDef("jcg_taint",
                "Bytecode-level taint verifier — has TWO modes:\n"
                        + "  (1) CHAIN MODE (preferred for high-signal): pass chain=[M:id1, M:id2, ...] + "
                        + "sink_api='M:...'. Verifies just that one path. Use this after jcg_trace finds a "
                        + "suspicious chain from a SERVLET/HTTP entry to a sink. PASS is high-value evidence.\n"
                        + "  (2) SCAN MODE (default if chain absent): for every dangerous sink, reverse-walks "
                        + "callers up to depth/budget limits and verifies each candidate. Underperforms on "
                        + "Spring/DI/BI code where taint crosses fields (the verifier has no heap model). "
                        + "On modern apps the verified-flow count is often 0; jcg_trace + jcg_sinks are more "
                        + "useful as primary scanners. FAIL is 'not proven', not 'safe'.");
        JsonObject taintProps = taintTool.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        JsonObject chainProp = new JsonObject();
        chainProp.addProperty("type", "array");
        chainProp.addProperty("description",
                "CHAIN MODE: list of M: ids (entry -> ... -> sink-calling method) to verify");
        JsonObject chainItems = new JsonObject();
        chainItems.addProperty("type", "string");
        chainProp.add("items", chainItems);
        taintProps.add("chain", chainProp);
        taintProps.add("sink_api", strProp(
                "CHAIN MODE: dangerous external API id (e.g. M:java/lang/Runtime#exec(Ljava/lang/String;)Ljava/lang/Process;)"));
        taintProps.add("category",
                strProp("SCAN MODE: optional category: exec|sql|deserialize|reflect|jndi|file|ssrf|xxe|expr|redirect"));
        taintProps.add("depth", intProp("SCAN MODE: max reverse-call depth per sink (default 8)"));
        taintProps.add("paths", intProp("SCAN MODE: max paths verified per sink (default 3)"));
        taintProps.add("budget",
                intProp("SCAN MODE: max total chains across all sinks (default 600); "
                        + "raise when output says BUDGET-EXHAUSTED"));
        addCommonOptionalProps(taintTool, scopeDesc, jsonDesc);
        tools.add(taintTool);

        JsonObject r = new JsonObject();
        r.add("tools", tools);
        return r;
    }

    private JsonObject toolsCall(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        Scope scope = Scope.of(argOrNull(args, "scope"));
        // PR-style path filter: caller passes the changed file paths as a JSON
        // array (e.g. extracted from a github `pull_request.changed_files`).
        if (args.has("changed_files") && args.get("changed_files").isJsonArray()) {
            java.util.Set<String> paths = new java.util.LinkedHashSet<>();
            for (JsonElement el : args.getAsJsonArray("changed_files")) {
                if (el.isJsonPrimitive()) {
                    paths.add(el.getAsString());
                }
            }
            scope = scope.withPaths(paths);
        }
        boolean wantJson = "json".equalsIgnoreCase(arg(args, "format"));
        // If a tool supports JSON and was asked for it, return a structured result
        // bypassing the text path entirely. Agents at scale need this to rank and
        // dedupe across hundreds of sinks without regex-parsing prose.
        if (wantJson) {
            JsonObject jsonResult = tryJsonDispatch(name, args, scope);
            if (jsonResult != null) {
                return wrapJson(jsonResult);
            }
        }
        String text;
        switch (name) {
            case "jcg_overview":
                text = security.overview(scope);
                break;
            case "jcg_status":
                text = nav.status();
                break;
            case "jcg_files":
                text = nav.files(argOrNull(args, "origin"), argOrNull(args, "language"),
                        intArg(args, "limit", 300));
                break;
            case "jcg_context":
                text = nav.context(arg(args, "task"));
                break;
            case "jcg_search":
                text = nav.search(arg(args, "query"));
                break;
            case "jcg_def":
                text = nav.def(arg(args, "name"));
                break;
            case "jcg_node":
                text = nav.node(arg(args, "symbol"));
                break;
            case "jcg_callers":
                text = nav.callers(arg(args, "method"), scope);
                break;
            case "jcg_callees":
                text = nav.callees(arg(args, "method"), scope);
                break;
            case "jcg_impact":
                text = nav.impact(arg(args, "symbol"), intArg(args, "depth", 2), scope);
                break;
            case "jcg_grep":
                text = nav.grep(arg(args, "pattern"), scope);
                break;
            case "jcg_outline":
                text = nav.outline(arg(args, "className"));
                break;
            case "jcg_source":
                text = sourceTool(arg(args, "className"));
                break;
            case "jcg_method":
                text = methodTool(arg(args, "className"), arg(args, "methodName"));
                break;
            case "jcg_sinks":
                text = security.sinks(argOrNull(args, "category"), scope);
                break;
            case "jcg_sources":
                text = security.sources(argOrNull(args, "category"), scope);
                break;
            case "jcg_trace":
                text = security.trace(arg(args, "method"), 12, 20, scope);
                break;
            case "jcg_taint": {
                // Preferred chain mode: caller passes a specific trace they already
                // triaged; we verify just that path. This is the high-signal use
                // for heap-heavy projects where the global scanner underperforms.
                java.util.List<String> chain = stringArrayArg(args, "chain");
                String sinkApi = argOrNull(args, "sink_api");
                if (!chain.isEmpty() && sinkApi != null) {
                    text = security.verifyChain(chain, sinkApi);
                } else {
                    text = security.taint(argOrNull(args, "category"),
                            intArg(args, "depth", 0),
                            intArg(args, "paths", 0),
                            intArg(args, "budget", 0),
                            scope);
                }
                break;
            }
            default:
                throw new RpcError(-32602, "unknown tool: " + name);
        }
        String raw = text == null ? "" : text;
        boolean truncated = raw.length() > MAX_OUTPUT_CHARS;
        String body = truncate(raw, name);
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", body);
        JsonArray contentArr = new JsonArray();
        contentArr.add(block);
        JsonObject r = new JsonObject();
        r.add("content", contentArr);
        r.addProperty("isError", false);
        if (truncated) {
            JsonObject meta = new JsonObject();
            meta.addProperty("truncated", true);
            r.add("_meta", meta);
        }
        return r;
    }

    /**
     * Returns a structured JSON result for tools that support it, or {@code null}
     * to fall through to the text path. Keeping the set explicit is intentional:
     * low-volume tools (status/def/outline/source/method) don't need JSON.
     */
    private JsonObject tryJsonDispatch(String name, JsonObject args, Scope scope) {
        switch (name) {
            case "jcg_sinks":
                return security.sinksJson(argOrNull(args, "category"), scope);
            case "jcg_sources":
                return security.sourcesJson(argOrNull(args, "category"), scope);
            case "jcg_taint": {
                java.util.List<String> chain = stringArrayArg(args, "chain");
                String sinkApi = argOrNull(args, "sink_api");
                if (!chain.isEmpty() && sinkApi != null) {
                    return security.verifyChainJson(chain, sinkApi);
                }
                return security.taintJson(argOrNull(args, "category"),
                        intArg(args, "depth", 0),
                        intArg(args, "paths", 0),
                        intArg(args, "budget", 0),
                        scope);
            }
            case "jcg_callers":
                return nav.callersJson(arg(args, "method"), scope);
            case "jcg_callees":
                return nav.calleesJson(arg(args, "method"), scope);
            case "jcg_impact":
                return nav.impactJson(arg(args, "symbol"), intArg(args, "depth", 2), scope);
            case "jcg_grep":
                return nav.grepJson(arg(args, "pattern"), scope);
            default:
                return null;
        }
    }

    private JsonObject wrapJson(JsonObject data) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", data.toString());
        JsonArray contentArr = new JsonArray();
        contentArr.add(block);
        JsonObject r = new JsonObject();
        r.add("content", contentArr);
        r.addProperty("isError", false);
        JsonObject meta = new JsonObject();
        meta.addProperty("json", true);
        r.add("_meta", meta);
        return r;
    }

    private String sourceTool(String className) {
        String src = content.classSource(Ids.clazz(className.replace('.', '/')));
        return src == null ? "class not found: " + className : src;
    }

    private String methodTool(String className, String methodName) {
        String internal = className.replace('.', '/');
        List<Node> methods = store.methodsOf(internal);
        StringBuilder sb = new StringBuilder();
        for (Node m : methods) {
            if (m.name.equals(methodName)) {
                sb.append("// ").append(m.owner).append('#').append(m.name).append(m.descriptor).append('\n');
                sb.append(content.methodSource(m.id)).append("\n\n");
            }
        }
        return sb.length() == 0 ? "method not found: " + className + "#" + methodName : sb.toString();
    }

    // ------------------------------------------------------------- json util

    private static String arg(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
    }

    /** Like {@link #arg} but returns null for an absent/blank value (optional filter). */
    private static String argOrNull(JsonObject args, String key) {
        String v = arg(args, key);
        return v.isEmpty() ? null : v;
    }

    /** Parse a string array arg (e.g. {@code "chain": ["M:a", "M:b"]}) into a Java list. */
    private static java.util.List<String> stringArrayArg(JsonObject args, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (args.has(key) && args.get(key).isJsonArray()) {
            for (JsonElement el : args.getAsJsonArray(key)) {
                if (el.isJsonPrimitive()) {
                    out.add(el.getAsString());
                }
            }
        }
        return out;
    }

    private static int intArg(JsonObject args, String key, int def) {
        if (!args.has(key) || args.get(key).isJsonNull()) {
            return def;
        }
        try {
            return args.get(key).getAsInt();
        } catch (Exception e) {
            return def;
        }
    }

    /** Add the optional {@code scope}, {@code format}, and {@code changed_files} parameters. */
    private static void addCommonOptionalProps(JsonObject tool, String scopeDesc, String jsonDesc) {
        JsonObject props = tool.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        props.add("scope", strProp(scopeDesc));
        props.add("format", strProp(jsonDesc));
        JsonObject changedFiles = new JsonObject();
        changedFiles.addProperty("type", "array");
        changedFiles.addProperty("description",
                "PR/review mode: list of changed file paths (relative or absolute); "
                        + "results are restricted to nodes whose source file matches one of these");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        changedFiles.add("items", items);
        props.add("changed_files", changedFiles);
    }

    private static JsonObject tool(String name, String desc, String argName, String argDesc) {
        JsonObject t = toolDef(name, desc);
        JsonObject schema = t.getAsJsonObject("inputSchema");
        schema.getAsJsonObject("properties").add(argName, strProp(argDesc));
        JsonArray req = new JsonArray();
        req.add(argName);
        schema.add("required", req);
        return t;
    }

    private static JsonObject toolDef(String name, String desc) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", desc);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        t.add("inputSchema", schema);
        return t;
    }

    private static JsonObject strProp(String desc) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        p.addProperty("description", desc);
        return p;
    }

    private static JsonObject intProp(String desc) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "integer");
        p.addProperty("description", desc);
        return p;
    }

    private static String truncate(String text, String toolName) {
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        int cut = text.lastIndexOf('\n', MAX_OUTPUT_CHARS);
        if (cut < MAX_OUTPUT_CHARS * 8 / 10) {
            cut = MAX_OUTPUT_CHARS;
        }
        return text.substring(0, cut)
                + "\n\n... (output truncated at " + MAX_OUTPUT_CHARS + " chars; "
                + "original length " + text.length() + ")"
                + "\n[truncated=true tool=" + toolName + "]"
                + "\nnext: " + nextActionHint(toolName);
    }

    /** Per-tool hint for the rare cases where a useful narrowing tip differs from the default. */
    private static String nextActionHint(String toolName) {
        switch (toolName) {
            case "jcg_source":
                return "use jcg_outline then jcg_method <className> <methodName> to pull a single method";
            case "jcg_taint":
                return "pass scope/category, or raise --budget for fuller coverage";
            case "jcg_sinks":
            case "jcg_sources":
                return "pass a category filter or --scope <pkg/prefix>";
            default:
                return "narrow with --scope <pkg/prefix>, a category filter, or a more specific id";
        }
    }

    private void writeResult(PrintWriter out, JsonElement id, JsonObject result) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        resp.add("result", result);
        out.println(resp);
    }

    private void writeError(PrintWriter out, JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        resp.add("error", err);
        out.println(resp);
    }

    private static final class RpcError extends RuntimeException {
        final int code;

        RpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
