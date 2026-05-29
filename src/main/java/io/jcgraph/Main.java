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
import io.jcgraph.query.Scope;
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
            case "sync":
                sync(db, a.opt("work", null), a.options.containsKey("no-materialize") ? Boolean.FALSE : null);
                break;
            case "status":
                withNav(db, NavigationService::status);
                break;
            case "files":
                withNav(db, nav -> nav.files(a.opt("origin", null), a.opt("language", null),
                        intOpt(a, "limit", 300)));
                break;
            case "search":
                requirePositional(a, 1, "search <query>");
                withNav(db, nav -> nav.search(a.positional.get(1)));
                break;
            case "def":
                requirePositional(a, 1, "def <name>");
                withNav(db, nav -> nav.def(a.positional.get(1)));
                break;
            case "node":
                requirePositional(a, 1, "node <id|name>");
                withNav(db, nav -> nav.node(a.positional.get(1)));
                break;
            case "callers":
                requirePositional(a, 1, "callers <method|id>");
                if (wantsJson(a)) {
                    withNav(db, nav -> nav.callersJson(a.positional.get(1), scopeOf(a)).toString());
                } else {
                    withNav(db, nav -> nav.callers(a.positional.get(1), scopeOf(a)));
                }
                break;
            case "callees":
                requirePositional(a, 1, "callees <method|id>");
                if (wantsJson(a)) {
                    withNav(db, nav -> nav.calleesJson(a.positional.get(1), scopeOf(a)).toString());
                } else {
                    withNav(db, nav -> nav.callees(a.positional.get(1), scopeOf(a)));
                }
                break;
            case "impact":
                requirePositional(a, 1, "impact <method|id> [--depth <n>]");
                if (wantsJson(a)) {
                    withNav(db, nav -> nav.impactJson(a.positional.get(1), intOpt(a, "depth", 2), scopeOf(a)).toString());
                } else {
                    withNav(db, nav -> nav.impact(a.positional.get(1), intOpt(a, "depth", 2), scopeOf(a)));
                }
                break;
            case "context":
                requirePositional(a, 1, "context <task>");
                withNav(db, nav -> nav.context(a.positional.get(1)));
                break;
            case "outline":
                requirePositional(a, 1, "outline <class>");
                withNav(db, nav -> nav.outline(a.positional.get(1)));
                break;
            case "grep":
                requirePositional(a, 1, "grep <pattern>");
                if (wantsJson(a)) {
                    withNav(db, nav -> nav.grepJson(a.positional.get(1), scopeOf(a)).toString());
                } else {
                    withNav(db, nav -> nav.grep(a.positional.get(1), scopeOf(a)));
                }
                break;
            case "source":
                requirePositional(a, 1, "source <class>");
                source(db, a.positional.get(1));
                break;
            case "method":
                requirePositional(a, 2, "method <class> <name>");
                method(db, a.positional.get(1), a.positional.get(2));
                break;
            case "overview":
                withSecurity(db, sec -> sec.overview(scopeOf(a)));
                break;
            case "sinks":
                if (wantsJson(a)) {
                    withSecurity(db, sec -> sec.sinksJson(
                            a.positional.size() > 1 ? a.positional.get(1) : null, scopeOf(a)).toString());
                } else {
                    withSecurity(db, sec -> sec.sinks(
                            a.positional.size() > 1 ? a.positional.get(1) : null, scopeOf(a)));
                }
                break;
            case "sources":
                if (wantsJson(a)) {
                    withSecurity(db, sec -> sec.sourcesJson(
                            a.positional.size() > 1 ? a.positional.get(1) : null, scopeOf(a)).toString());
                } else {
                    withSecurity(db, sec -> sec.sources(
                            a.positional.size() > 1 ? a.positional.get(1) : null, scopeOf(a)));
                }
                break;
            case "trace":
                requirePositional(a, 1, "trace <method|id>");
                withSecurity(db, sec -> sec.trace(a.positional.get(1),
                        12, 20, scopeOf(a)));
                break;
            case "taint":
                // Chain mode: --chain "M:a,M:b,..." --sink-api "M:..." — verify
                // a specific chain (typically one the user found via jcg_trace).
                // This is the preferred high-signal use; the scanning mode below
                // underperforms on heap-heavy code.
                String chainArg = a.options.get("chain");
                if (chainArg != null && !chainArg.isEmpty()) {
                    String sinkApi = a.options.get("sink-api");
                    if (sinkApi == null || sinkApi.isEmpty()) {
                        System.err.println("--chain requires --sink-api <M:...>");
                        System.exit(2);
                    }
                    List<String> chain = new ArrayList<>();
                    for (String s : chainArg.split(",")) {
                        String t = s.trim();
                        if (!t.isEmpty()) chain.add(t);
                    }
                    final String sa = sinkApi;
                    if (wantsJson(a)) {
                        withSecurity(db, sec -> sec.verifyChainJson(chain, sa).toString());
                    } else {
                        withSecurity(db, sec -> sec.verifyChain(chain, sa));
                    }
                    break;
                }
                if (wantsJson(a)) {
                    withSecurity(db, sec -> sec.taintJson(
                            a.positional.size() > 1 ? a.positional.get(1) : null,
                            intOpt(a, "depth", 0),
                            intOpt(a, "paths", 0),
                            intOpt(a, "budget", 0),
                            scopeOf(a)).toString());
                } else {
                    withSecurity(db, sec -> sec.taint(
                            a.positional.size() > 1 ? a.positional.get(1) : null,
                            intOpt(a, "depth", 0),
                            intOpt(a, "paths", 0),
                            intOpt(a, "budget", 0),
                            scopeOf(a)));
                }
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
        if (!collected.duplicates.isEmpty()) {
            System.out.println("[index] WARNING: " + collected.duplicates.size()
                    + " duplicate classes encountered (same internal name in multiple jars);"
                    + " keeping first occurrence, see `jcgraph status` for details");
        }

        BytecodeExtractor be = new BytecodeExtractor();
        for (Collected.ClassUnit cu : collected.classes) {
            be.extract(cu);
        }
        SourceExtractor se = new SourceExtractor();
        for (Collected.SourceUnit su : collected.sources) {
            se.extract(su);
        }
        // Second pass: rewrite placeholder descriptors on cross-class source CALLS
        // edges now that we have the full source method directory.
        se.linkCalls();

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

        try (GraphStore store = GraphStore.open(db)) {
            store.replaceBatch(nodes, edges, files);
            store.putMeta("input", input.toString());
            store.putMeta("work_dir", workDir);
            store.putMeta("materialize", String.valueOf(materialize));
            store.putMeta("indexed_at", String.valueOf(System.currentTimeMillis()));
            store.putMeta("duplicate_classes", String.valueOf(collected.duplicates.size()));
            if (!collected.duplicates.isEmpty()) {
                StringBuilder sample = new StringBuilder();
                int n = Math.min(10, collected.duplicates.size());
                for (int i = 0; i < n; i++) {
                    Collected.DuplicateClass d = collected.duplicates.get(i);
                    if (sample.length() > 0) {
                        sample.append('\n');
                    }
                    sample.append(d.internalName).append(" (from ")
                            .append(d.container == null ? "-" : d.container)
                            .append(", first seen in ")
                            .append(d.firstContainer == null ? "-" : d.firstContainer)
                            .append(')');
                }
                store.putMeta("duplicate_classes_sample", sample.toString());
            } else {
                store.putMeta("duplicate_classes_sample", "");
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("[index] done in " + ms + " ms");
            System.out.println("[index] nodes=" + store.count("nodes")
                    + " edges=" + store.count("edges"));
        }
    }

    private static void sync(String db, String workOverride, Boolean materializeOverride) {
        String input;
        String workDir;
        boolean materialize;
        boolean stale;
        try (GraphStore store = GraphStore.open(db)) {
            input = store.getMeta("input");
            workDir = workOverride != null ? workOverride : store.getMeta("work_dir");
            String lastMaterialize = store.getMeta("materialize");
            materialize = materializeOverride != null
                    ? materializeOverride
                    : (lastMaterialize == null || Boolean.parseBoolean(lastMaterialize));
            if (input == null || input.isEmpty()) {
                System.out.println("[sync] no previous input recorded; run jcgraph index <input> first");
                return;
            }
            stale = store.inputChangedSinceIndex()
                    || !store.changedFiles(1).isEmpty()
                    || !store.addedFiles(1).isEmpty();
        }
        if (!stale) {
            System.out.println("[sync] index is up to date");
            return;
        }
        if (workDir == null || workDir.isEmpty()) {
            workDir = db + ".work";
        }
        System.out.println("[sync] changes detected; rebuilding index");
        index(Paths.get(input), db, workDir, materialize);
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
                    System.out.println("// " + m.owner + "#" + m.name + m.descriptor);
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
        System.out.println("  sync    [--db <path>] [--work <dir>] [--no-materialize]");
        System.out.println("  status  [--db <path>]");
        System.out.println("  files   [--db <path>] [--origin <origin>] [--language <language>] [--limit <n>]");
        System.out.println("  search  <query>  [--db <path>]");
        System.out.println("  def     <name>   [--db <path>]");
        System.out.println("  node    <id|name> [--db <path>]   exact node details and class outline");
        System.out.println("  callers <method|id> [--scope <pkg/prefix>] [--db <path>]");
        System.out.println("  callees <method|id> [--scope <pkg/prefix>] [--db <path>]");
        System.out.println("  impact  <method|id> [--depth <n>] [--scope <pkg/prefix>] [--db <path>]");
        System.out.println("  context <task> [--db <path>]   compact agent context from search + call graph");
        System.out.println("  source  <class>  [--db <path>]");
        System.out.println("  method  <class> <name> [--db <path>]");
        System.out.println("  outline <class>  [--db <path>]   class method skeleton (ids + line ranges)");
        System.out.println("  grep    <pattern> [--scope <pkg/prefix>] [--db <path>]");
        System.out.println("  overview         [--scope <pkg/prefix>] [--db <path>]");
        System.out.println("  sinks   [category] [--scope <pkg/prefix>] [--db <path>]");
        System.out.println("  sources [category] [--scope <pkg/prefix>] [--db <path>]");
        System.out.println("  trace   <method|id> [--scope <pkg/prefix>] [--db <path>]");
        System.out.println("  taint --chain <M:a,M:b,...> --sink-api <M:...> [--db <path>]");
        System.out.println("            VERIFY one chain (preferred — high signal). Use after `trace` finds a suspect path.");
        System.out.println("  taint   [category] [--depth <n>] [--paths <n>] [--budget <n>] [--scope <pkg>] [--db <path>]");
        System.out.println("            SCAN mode: best-effort over all sinks. Often 0 flows on Spring/DI/BI code");
        System.out.println("            (no heap model). Prefer the --chain form above.");
        System.out.println("  serve   --mcp    [--db <path>]   (MCP server over stdio)");
        System.out.println("\ndefault db: " + DEFAULT_DB);
        System.out.println("--scope examples: --scope com/myorg/  (slashes), --scope com.myorg  (dots; both work)");
        System.out.println("--json on: sinks|sources|taint|callers|callees|impact|grep   structured output for agents");
        System.out.println("PR mode: --changed-files <file>   restrict to paths listed in file (one per line)");
        System.out.println("         --since <git-ref>         run `git diff --name-only <ref>...HEAD` and restrict");
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

    private static int intOpt(Args a, String key, int def) {
        String v = a.options.get(key);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Scope scopeOf(Args a) {
        Scope scope = Scope.of(a.options.get("scope"));
        java.util.Set<String> changed = collectChangedPaths(a);
        return changed.isEmpty() ? scope : scope.withPaths(changed);
    }

    /**
     * Build the set of changed-file paths from {@code --changed-files <file>}
     * (one path per line) and/or {@code --since <git-ref>} (runs
     * {@code git diff --name-only <ref>...HEAD} in the cwd). Lets an auditor
     * scope an entire query down to a PR's footprint.
     */
    private static java.util.Set<String> collectChangedPaths(Args a) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String filePath = a.options.get("changed-files");
        if (filePath != null && !filePath.isEmpty()) {
            try {
                for (String line : java.nio.file.Files.readAllLines(
                        java.nio.file.Paths.get(filePath),
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        out.add(trimmed);
                    }
                }
            } catch (java.io.IOException e) {
                System.err.println("[scope] cannot read --changed-files " + filePath + ": " + e);
            }
        }
        String since = a.options.get("since");
        if (since != null && !since.isEmpty()) {
            try {
                ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", since + "...HEAD");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream(),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) {
                            out.add(trimmed);
                        }
                    }
                }
                proc.waitFor();
            } catch (Exception e) {
                System.err.println("[scope] git diff for --since " + since + " failed: " + e);
            }
        }
        return out;
    }

    /** {@code --json} prints structured output for the agent-chainable tools. */
    private static boolean wantsJson(Args a) {
        return a.options.containsKey("json");
    }
}
