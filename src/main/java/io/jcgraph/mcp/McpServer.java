package io.jcgraph.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.jcgraph.content.ContentResolver;
import io.jcgraph.model.Ids;
import io.jcgraph.model.Node;
import io.jcgraph.query.NavigationService;
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
                "Java code graph over jar/war/class/jmod/java. All tools are prefixed jcg_ and operate "
                        + "ONLY on the indexed Java project (not the local filesystem). Use jcg_search/jcg_def "
                        + "to locate symbols, jcg_callers/jcg_callees for the call graph (expanded through "
                        + "overrides, so interface/abstract calls reach implementations), jcg_source/jcg_method "
                        + "to read code (decompiled for bytecode), jcg_grep to search the indexed Java text. "
                        + "For vulnerability hunting: jcg_sinks lists dangerous call sites, jcg_sources lists "
                        + "untrusted-input reads, jcg_trace shows reverse call chains from a sink-calling method "
                        + "to entry points. Trust results; no need to re-verify with the built-in grep.");
        return r;
    }

    private JsonObject toolsList() {
        JsonArray tools = new JsonArray();
        tools.add(tool("jcg_search", "Find Java symbols (class/method/field) by name in the jcgraph index.",
                "query", "symbol name or substring"));
        tools.add(tool("jcg_def", "Show a Java symbol's definition (kind, owner, location, descriptor, "
                        + "signature) from the jcgraph index.",
                "name", "symbol name"));
        tools.add(tool("jcg_callers", "List methods that call the given method (jcgraph call graph).",
                "method", "method name or full id (M:owner#name<desc>)"));
        tools.add(tool("jcg_callees", "List methods called by the given method (jcgraph call graph).",
                "method", "method name or full id (M:owner#name<desc>)"));
        tools.add(tool("jcg_grep", "Search the INDEXED Java code in the jcgraph database (string constants "
                        + "+ decompiled/native .java). NOT a filesystem grep — it only searches the indexed Java project.",
                "pattern", "text to search for"));
        tools.add(tool("jcg_source", "Print a Java class's source from the jcgraph index "
                        + "(decompiled if it came from bytecode).",
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
        tools.add(sinksTool);

        JsonObject sourcesTool = toolDef("jcg_sources",
                "List untrusted-input SOURCE read sites (e.g. HttpServletRequest#getParameter/getHeader). "
                        + "Optional category filter.");
        sourcesTool.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("category",
                strProp("optional category: http|net"));
        tools.add(sourcesTool);

        tools.add(tool("jcg_trace",
                "Reverse call chains (call-reachability) from a method up to entry points / "
                        + "source-reading methods. Expands through OVERRIDES so chains flow through "
                        + "interface/abstract calls into implementations. Use on a sink-calling method.",
                "method", "method name or full id (M:owner#name<desc>)"));

        JsonObject taintTool = toolDef("jcg_taint",
                "Taint-verified source->sink data-flow: for each dangerous sink call site, traces back "
                        + "to entries and simulates per-method bytecode taint, reporting only flows where "
                        + "untrusted input provably reaches the dangerous call. Stronger than jcg_trace "
                        + "(data-flow, not just call-reachability). A FAIL is 'not proven', not 'safe'.");
        taintTool.getAsJsonObject("inputSchema").getAsJsonObject("properties").add("category",
                strProp("optional category: exec|sql|deserialize|reflect|jndi|file|ssrf|xxe|expr|redirect"));
        tools.add(taintTool);

        JsonObject r = new JsonObject();
        r.add("tools", tools);
        return r;
    }

    private JsonObject toolsCall(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        String text;
        switch (name) {
            case "jcg_search":
                text = nav.search(arg(args, "query"));
                break;
            case "jcg_def":
                text = nav.def(arg(args, "name"));
                break;
            case "jcg_callers":
                text = nav.callers(arg(args, "method"));
                break;
            case "jcg_callees":
                text = nav.callees(arg(args, "method"));
                break;
            case "jcg_grep":
                text = nav.grep(arg(args, "pattern"));
                break;
            case "jcg_source":
                text = sourceTool(arg(args, "className"));
                break;
            case "jcg_method":
                text = methodTool(arg(args, "className"), arg(args, "methodName"));
                break;
            case "jcg_sinks":
                text = security.sinks(argOrNull(args, "category"));
                break;
            case "jcg_sources":
                text = security.sources(argOrNull(args, "category"));
                break;
            case "jcg_trace":
                text = security.trace(arg(args, "method"));
                break;
            case "jcg_taint":
                text = security.taint(argOrNull(args, "category"));
                break;
            default:
                throw new RpcError(-32602, "unknown tool: " + name);
        }
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text == null ? "" : text);
        JsonArray contentArr = new JsonArray();
        contentArr.add(block);
        JsonObject r = new JsonObject();
        r.add("content", contentArr);
        r.addProperty("isError", false);
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
                sb.append("// ").append(m.owner).append('#').append(m.name).append(m.descriptor)
                        .append("   <").append(m.origin.tag()).append(">\n");
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
