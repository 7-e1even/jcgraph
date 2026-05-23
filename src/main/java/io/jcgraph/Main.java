package io.jcgraph;

import io.jcgraph.collect.Collected;
import io.jcgraph.collect.FileCollector;
import io.jcgraph.content.ContentResolver;
import io.jcgraph.content.Materializer;
import io.jcgraph.extract.BytecodeExtractor;
import io.jcgraph.extract.CallGraphLinker;
import io.jcgraph.extract.SourceExtractor;
import io.jcgraph.model.Edge;
import io.jcgraph.model.Ids;
import io.jcgraph.model.Node;
import io.jcgraph.query.NavigationService;
import io.jcgraph.query.SecurityService;
import io.jcgraph.store.GraphStore;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * jcgraph CLI.
 *
 * <pre>
 *   jcgraph index  &lt;input&gt; [--db &lt;path&gt;] [--work &lt;dir&gt;]   build the graph (jar/war/class/jmod/java/dir)
 *   jcgraph search &lt;query&gt;  [--db &lt;path&gt;]                  find symbols by name
 *   jcgraph def    &lt;name&gt;   [--db &lt;path&gt;]                  show a symbol's definition + signature
 *   jcgraph callers &lt;method|id&gt; [--db &lt;path&gt;]              who calls this method
 *   jcgraph callees &lt;method|id&gt; [--db &lt;path&gt;]              what this method calls
 *   jcgraph source &lt;class&gt;  [--db &lt;path&gt;]                  print class source (decompiled if bytecode)
 *   jcgraph method &lt;class&gt; &lt;name&gt; [--db &lt;path&gt;]            print a method's source
 *   jcgraph grep   &lt;pattern&gt;[--db &lt;path&gt;]                  literal grep (constants + source files)
 * </pre>
 */
public class Main {

    private static final String DEFAULT_DB = ".jcgraph/index.db";

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        String cmd = args[0];
        Args a = Args.parse(args);
        String db = a.opt("db", DEFAULT_DB);

        switch (cmd) {
            case "index":
                requirePositional(a, 1, "index <input>");
                index(Paths.get(a.positional.get(1)), db, a.opt("work", db + ".work"),
                        !a.options.containsKey("no-materialize"));
                break;
            case "search":
                requirePositional(a, 1, "search <query>");
                withNav(db, nav -> nav.search(a.positional.get(1)));
                break;
            case "def":
                requirePositional(a, 1, "def <name>");
                withNav(db, nav -> nav.def(a.positional.get(1)));
                break;
            case "callers":
                requirePositional(a, 1, "callers <method|id>");
                withNav(db, nav -> nav.callers(a.positional.get(1)));
                break;
            case "callees":
                requirePositional(a, 1, "callees <method|id>");
                withNav(db, nav -> nav.callees(a.positional.get(1)));
                break;
            case "grep":
                requirePositional(a, 1, "grep <pattern>");
                withNav(db, nav -> nav.grep(a.positional.get(1)));
                break;
            case "source":
                requirePositional(a, 1, "source <class>");
                source(db, a.positional.get(1));
                break;
            case "method":
                requirePositional(a, 2, "method <class> <name>");
                method(db, a.positional.get(1), a.positional.get(2));
                break;
            case "sinks":
                withSecurity(db, sec -> sec.sinks(a.positional.size() > 1 ? a.positional.get(1) : null));
                break;
            case "sources":
                withSecurity(db, sec -> sec.sources(a.positional.size() > 1 ? a.positional.get(1) : null));
                break;
            case "trace":
                requirePositional(a, 1, "trace <method|id>");
                withSecurity(db, sec -> sec.trace(a.positional.get(1)));
                break;
            case "taint":
                withSecurity(db, sec -> sec.taint(a.positional.size() > 1 ? a.positional.get(1) : null));
                break;
            case "serve":
                new io.jcgraph.mcp.McpServer(db).run();
                break;
            default:
                System.err.println("unknown command: " + cmd);
                printUsage();
        }
    }

    // ------------------------------------------------------------------ index

    private static void index(Path input, String db, String workDir, boolean materialize) {
        long t0 = System.nanoTime();
        System.out.println("[index] input=" + input + " db=" + db + " work=" + workDir
                + " materialize=" + materialize);

        FileCollector collector = new FileCollector(Paths.get(workDir));
        Collected collected = collector.collect(input);
        System.out.println("[index] collected " + collected.classes.size() + " classes, "
                + collected.sources.size() + " sources");

        BytecodeExtractor be = new BytecodeExtractor();
        for (Collected.ClassUnit cu : collected.classes) {
            be.extract(cu);
        }
        SourceExtractor se = new SourceExtractor();
        for (Collected.SourceUnit su : collected.sources) {
            se.extract(su);
        }

        // Global link pass: resolve polymorphism into OVERRIDES edges so the call graph
        // can be expanded through interfaces/abstract methods at query time (taint flow).
        List<Edge> overrides = new CallGraphLinker().link(be.nodes, be.edges);
        be.edges.addAll(overrides);
        System.out.println("[index] linked " + overrides.size() + " overrides edges");

        List<String[]> files = new ArrayList<>();
        for (Collected.ClassUnit cu : collected.classes) {
            files.add(new String[]{cu.classFile.toString(), "bytecode", cu.container, "class"});
        }
        for (Collected.SourceUnit su : collected.sources) {
            files.add(new String[]{su.javaFile.toString(), "source", su.container, "java"});
        }

        // Hybrid: decompile bytecode to a .java mirror and enrich node positions in place,
        // so content + grep operate uniformly on .java.
        if (materialize && !be.nodes.isEmpty()) {
            List<String> mat = new Materializer(Paths.get(workDir)).materialize(be.nodes);
            for (String p : mat) {
                files.add(new String[]{p, "decompiled", null, "java"});
            }
            System.out.println("[index] materialized " + mat.size() + " decompiled .java");
        }

        List<Node> nodes = new ArrayList<>(be.nodes);
        nodes.addAll(se.nodes);
        List<Edge> edges = new ArrayList<>(be.edges);
        edges.addAll(se.edges);
        List<String[]> strings = new ArrayList<>(be.strings);
        strings.addAll(se.strings);

        try (GraphStore store = GraphStore.open(db)) {
            store.writeBatch(nodes, edges, strings, files);
            store.putMeta("input", input.toString());
            store.putMeta("work_dir", workDir);
            store.putMeta("indexed_at", String.valueOf(System.currentTimeMillis()));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("[index] done in " + ms + " ms");
            System.out.println("[index] nodes=" + store.count("nodes")
                    + " edges=" + store.count("edges")
                    + " strings=" + store.count("strings"));
        }
    }

    // --------------------------------------------------------------- content

    private static void source(String db, String className) {
        try (GraphStore store = GraphStore.open(db)) {
            String internal = className.replace('.', '/');
            ContentResolver resolver = new ContentResolver(store);
            String src = resolver.classSource(Ids.clazz(internal));
            if (src == null) {
                System.out.println("class not found: " + className);
                System.out.println("try: jcgraph search " + simpleName(className) + " --db " + db);
                return;
            }
            System.out.println(src);
        }
    }

    private static void method(String db, String className, String methodName) {
        try (GraphStore store = GraphStore.open(db)) {
            String internal = className.replace('.', '/');
            List<Node> methods = store.methodsOf(internal);
            ContentResolver resolver = new ContentResolver(store);
            int printed = 0;
            for (Node m : methods) {
                if (m.name.equals(methodName)) {
                    System.out.println("// " + m.owner + "#" + m.name + m.descriptor
                            + "   <" + m.origin.tag() + ">");
                    System.out.println(resolver.methodSource(m.id));
                    System.out.println();
                    printed++;
                }
            }
            if (printed == 0) {
                System.out.println("method not found: " + className + "#" + methodName);
                System.out.println("try: jcgraph source " + className + " --db " + db);
            }
        }
    }

    // ----------------------------------------------------------------- helper

    private interface NavFn {
        String apply(NavigationService nav);
    }

    private static void withNav(String db, NavFn fn) {
        try (GraphStore store = GraphStore.open(db)) {
            System.out.println(fn.apply(new NavigationService(store)));
        }
    }

    private interface SecFn {
        String apply(SecurityService sec);
    }

    private static void withSecurity(String db, SecFn fn) {
        try (GraphStore store = GraphStore.open(db)) {
            System.out.println(fn.apply(new SecurityService(store)));
        }
    }

    private static String simpleName(String className) {
        String s = className.replace('/', '.');
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static void requirePositional(Args a, int n, String usage) {
        if (a.positional.size() <= n) {
            System.err.println("usage: jcgraph " + usage);
            System.exit(2);
        }
    }

    private static void printUsage() {
        System.out.println("jcgraph - unified Java code graph (jar/war/class/jmod/java)\n");
        System.out.println("  index   <input> [--db <path>] [--work <dir>] [--no-materialize]");
        System.out.println("  search  <query>  [--db <path>]");
        System.out.println("  def     <name>   [--db <path>]");
        System.out.println("  callers <method|id> [--db <path>]");
        System.out.println("  callees <method|id> [--db <path>]");
        System.out.println("  source  <class>  [--db <path>]");
        System.out.println("  method  <class> <name> [--db <path>]");
        System.out.println("  grep    <pattern> [--db <path>]");
        System.out.println("  sinks   [category] [--db <path>]   dangerous call sites (exec/sql/deserialize/...)");
        System.out.println("  sources [category] [--db <path>]   untrusted-input read sites");
        System.out.println("  trace   <method|id> [--db <path>]  reverse call chains to entry points (call-reachable)");
        System.out.println("  taint   [category] [--db <path>]   taint-verified source->sink flows (data-flow)");
        System.out.println("  serve   --mcp    [--db <path>]   (MCP server over stdio)");
        System.out.println("\ndefault db: " + DEFAULT_DB);
    }

    /** Minimal arg parser: positional args + --key value options. */
    private static final class Args {
        final List<String> positional = new ArrayList<>();
        final Map<String, String> options = new HashMap<>();

        String opt(String key, String def) {
            return options.getOrDefault(key, def);
        }

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                String s = argv[i];
                if (s.startsWith("--")) {
                    String key = s.substring(2);
                    // a value belongs to this flag only if the next token isn't another flag
                    if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                        a.options.put(key, argv[++i]);
                    } else {
                        a.options.put(key, ""); // boolean flag, e.g. --full
                    }
                } else {
                    a.positional.add(s);
                }
            }
            return a;
        }
    }
}
