package io.jcgraph.content;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import io.jcgraph.model.Descriptors;
import io.jcgraph.model.Node;
import io.jcgraph.model.NodeKind;
import io.jcgraph.model.SourceDescriptors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hybrid-mode materialization: decompile every top-level bytecode class to
 * {@code <work>/.sources/<internal>.java}, then enrich the (ASM-built) nodes in
 * place so they point at the decompiled .java with accurate line ranges.
 *
 * <p>ASM stays the source of truth for the graph (precise descriptors + call
 * edges); the decompiled .java becomes a first-class content + grep layer, so
 * {@code source}/{@code method}/{@code grep} all operate uniformly on .java.</p>
 */
public class Materializer {

    /** JavaParser is not thread-safe; give each worker thread its own instance. */
    private static final ThreadLocal<JavaParser> PARSER = ThreadLocal.withInitial(JavaParser::new);

    private final Decompiler decompiler = new Decompiler();
    private final Path workDir;
    private final Path sourcesDir;

    public Materializer(Path workDir) {
        this.workDir = workDir;
        this.sourcesDir = workDir.resolve(".sources");
    }

    /** Returns the materialized .java paths; enriches {@code bytecodeNodes} in place. */
    public List<String> materialize(List<Node> bytecodeNodes) {
        Map<String, List<Node>> byTop = new HashMap<>();
        for (Node n : bytecodeNodes) {
            String internal = isType(n) ? n.id.substring(2) : n.owner;
            if (internal == null) {
                continue;
            }
            byTop.computeIfAbsent(topLevel(internal), k -> new ArrayList<>()).add(n);
        }

        // Decompile + parse are CPU-bound and independent per top-level class -> fan out across cores.
        List<String> javaPaths = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger done = new AtomicInteger();
        AtomicInteger reused = new AtomicInteger();
        int threads = threadCount();
        System.out.println("[materialize] decompiling " + byTop.size()
                + " classes across " + threads + " threads (resume: reusing existing .java)");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>(byTop.size());
        for (Map.Entry<String, List<Node>> e : byTop.entrySet()) {
            futures.add(pool.submit(() -> materializeOne(e.getKey(), e.getValue(), javaPaths, done, reused)));
        }
        pool.shutdown();
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException ex) {
                System.err.println("[materialize] task failed: " + ex.getCause());
            }
        }
        System.out.println("[materialize] complete: " + done.get() + " classes ("
                + reused.get() + " reused from a previous run, "
                + (done.get() - reused.get()) + " freshly decompiled)");
        return javaPaths;
    }

    private void materializeOne(String top, List<Node> group, List<String> javaPaths,
                               AtomicInteger done, AtomicInteger reused) {
        Path classFile = workDir.resolve(top + ".class");
        if (!Files.exists(classFile)) {
            return;
        }
        Path javaPath = sourcesDir.resolve(top + ".java");

        // Resume: a usable .java from a previous run lets us skip the expensive CFR pass.
        // (We still re-parse it below: node line ranges are rebuilt in memory each run.)
        String src = reusableSource(javaPath);
        if (src != null) {
            reused.incrementAndGet();
        } else {
            src = decompiler.decompile(classFile.toString());
            try {
                writeAtomically(javaPath, src);
            } catch (Exception ex) {
                System.err.println("[materialize] write failed " + javaPath + ": " + ex);
                return;
            }
        }
        javaPaths.add(javaPath.toString());

        Ranges ranges = null;
        try {
            ParseResult<CompilationUnit> pr = PARSER.get().parse(src);
            if (pr.isSuccessful() && pr.getResult().isPresent()) {
                ranges = collectRanges(pr.getResult().get());
            }
        } catch (Exception ex) {
            // unparseable decompiler output -> file still grep-able, skip position enrichment
        }
        if (ranges != null) {
            enrich(group, javaPath.toString(), ranges);
        }
        int d = done.incrementAndGet();
        if (d % 200 == 0) {
            System.out.println("[materialize] processed " + d + " classes");
        }
    }

    /**
     * Write {@code src} so the {@code .java} only ever exists fully-formed: a Ctrl+C mid-write
     * leaves a stray {@code .tmp} (ignored on resume), never a truncated {@code .java}.
     */
    private void writeAtomically(Path javaPath, String src) throws IOException {
        Files.createDirectories(javaPath.getParent());
        Path tmp = javaPath.resolveSibling(javaPath.getFileName() + ".tmp");
        Files.write(tmp, src.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, javaPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, javaPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Returns the on-disk source if a prior run already materialized a usable {@code .java}
     * (non-empty, not a decompile-failure placeholder), else {@code null} so it gets retried.
     */
    private String reusableSource(Path javaPath) {
        try {
            if (!Files.exists(javaPath) || Files.size(javaPath) == 0) {
                return null;
            }
            String s = new String(Files.readAllBytes(javaPath), StandardCharsets.UTF_8);
            if (s.startsWith("// decompile failed for ")
                    || s.startsWith("// decompiler produced no output for ")) {
                return null; // a failed attempt from last run -> decompile again
            }
            return s;
        } catch (IOException ex) {
            return null;
        }
    }

    private static int threadCount() {
        // -Djcgraph.materialize.threads=N (or JCGRAPH_THREADS env, reachable through the launcher)
        int override = parsePositive(System.getProperty("jcgraph.materialize.threads"));
        if (override <= 0) {
            override = parsePositive(System.getenv("JCGRAPH_THREADS"));
        }
        return override > 0 ? override : Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private static int parsePositive(String s) {
        if (s == null) {
            return -1;
        }
        try {
            int n = Integer.parseInt(s.trim());
            return n > 0 ? n : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void enrich(List<Node> group, String javaPath, Ranges r) {
        for (Node n : group) {
            int[] rng = null;
            if (isType(n)) {
                rng = r.types.get(n.id.substring(2));
            } else if (n.kind == NodeKind.METHOD) {
                // Prefer full-descriptor match (disambiguates overloads with the same
                // paramCount); fall back to paramCount only when descriptor is unknown
                // OR when the descriptor key has no match (decompiler-side resolution
                // mismatch). If paramCount key collides, leave unresolved rather than
                // pick at random.
                String descKey = n.owner + "#" + n.name + "#" + (n.descriptor == null ? "" : n.descriptor);
                rng = r.methodsByDesc.get(descKey);
                if (rng == null) {
                    String pcKey = n.owner + "#" + n.name + "#" + Descriptors.paramCount(n.descriptor);
                    Integer ambiguousCount = r.methodAmbiguity.get(pcKey);
                    if (ambiguousCount != null && ambiguousCount == 1) {
                        rng = r.methods.get(pcKey);
                    }
                }
            } else if (n.kind == NodeKind.FIELD) {
                rng = r.fields.get(n.owner + "#" + n.name);
            }
            if (rng != null) {
                n.filePath = javaPath;
                n.startLine = rng[0];
                n.endLine = rng[1];
            }
        }
    }

    private static boolean isType(Node n) {
        return n.kind == NodeKind.CLASS || n.kind == NodeKind.INTERFACE
                || n.kind == NodeKind.ENUM || n.kind == NodeKind.ANNOTATION;
    }

    private static String topLevel(String internal) {
        int d = internal.indexOf('$');
        return d >= 0 ? internal.substring(0, d) : internal;
    }

    // ----------------------------------------------------- range collection

    private static final class Ranges {
        final Map<String, int[]> types = new HashMap<>();
        // primary key: owner#name#descriptor (disambiguates overloads)
        final Map<String, int[]> methodsByDesc = new HashMap<>();
        // legacy paramCount key, used only when 1 candidate exists for that count
        final Map<String, int[]> methods = new HashMap<>();
        final Map<String, Integer> methodAmbiguity = new HashMap<>();
        final Map<String, int[]> fields = new HashMap<>();
    }

    private Ranges collectRanges(CompilationUnit cu) {
        Ranges r = new Ranges();
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        SourceDescriptors sd = SourceDescriptors.of(cu);
        for (TypeDeclaration<?> td : cu.getTypes()) {
            collectType(td, null, pkg, sd, r);
        }
        return r;
    }

    private void collectType(TypeDeclaration<?> td, String enclosing, String pkg,
                             SourceDescriptors sd, Ranges r) {
        String name = td.getNameAsString();
        String internal = enclosing == null
                ? (pkg.isEmpty() ? name : pkg.replace('.', '/') + "/" + name)
                : enclosing + "$" + name;

        td.getRange().ifPresent(rg -> r.types.put(internal, new int[]{rg.begin.line, rg.end.line}));
        for (MethodDeclaration md : td.getMethods()) {
            md.getRange().ifPresent(rg -> {
                int[] range = new int[]{rg.begin.line, rg.end.line};
                String descKey = internal + "#" + md.getNameAsString() + "#"
                        + sd.methodDescriptor(md.getParameters(), md.getType());
                r.methodsByDesc.put(descKey, range);
                String pcKey = internal + "#" + md.getNameAsString() + "#" + md.getParameters().size();
                r.methods.put(pcKey, range);
                r.methodAmbiguity.merge(pcKey, 1, Integer::sum);
            });
        }
        for (ConstructorDeclaration cd : td.getConstructors()) {
            cd.getRange().ifPresent(rg -> {
                int[] range = new int[]{rg.begin.line, rg.end.line};
                String descKey = internal + "#<init>#"
                        + sd.methodDescriptor(cd.getParameters(), null);
                r.methodsByDesc.put(descKey, range);
                String pcKey = internal + "#<init>#" + cd.getParameters().size();
                r.methods.put(pcKey, range);
                r.methodAmbiguity.merge(pcKey, 1, Integer::sum);
            });
        }
        for (FieldDeclaration fd : td.getFields()) {
            for (VariableDeclarator v : fd.getVariables()) {
                fd.getRange().ifPresent(rg -> r.fields.put(
                        internal + "#" + v.getNameAsString(),
                        new int[]{rg.begin.line, rg.end.line}));
            }
        }
        td.getMembers().forEach(m -> {
            if (m instanceof TypeDeclaration) {
                collectType((TypeDeclaration<?>) m, internal, pkg, sd, r);
            }
        });
    }
}
