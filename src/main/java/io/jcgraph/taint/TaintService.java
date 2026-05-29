package io.jcgraph.taint;

import io.jcgraph.security.RuleCatalog;
import org.objectweb.asm.ClassReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Chain-driven taint verification. Given a call chain (entry → ... → sink method)
 * plus the dangerous sink API, simulates each method in turn (in-method ASM
 * abstract interpretation) and threads the taint payload hop to hop. PASS means
 * untrusted input (introduced by a source-rule call) reaches the dangerous call's
 * arguments via call propagation + library propagation rules.
 *
 * <p>This is the jcgraph orchestration replacing jar-analyzer's TaintAnalyzer.
 * Known approximation: no heap/field modeling (covered partially by propagation
 * rules), so a FAIL is "not proven", not "safe".</p>
 */
public class TaintService {

    private final Path workDir;
    private final RuleCatalog catalog;
    private final PropagationRuleSet propagation;
    private final SanitizerRule sanitizer;

    public TaintService(Path workDir, RuleCatalog catalog) {
        this.workDir = workDir;
        this.catalog = catalog;
        this.propagation = PropagationRuleSet.loadJSON(
                TaintService.class.getResourceAsStream("/propagation.json"));
        this.sanitizer = SanitizerRule.loadJSON(
                TaintService.class.getResourceAsStream("/sanitizer.json"));
    }

    /**
     * Verify a chain. {@code methodChainIds} is entry → ... → sinkMethod (jcgraph
     * method ids). {@code sinkApiId} is the dangerous API the last method calls
     * (e.g. {@code M:java/lang/Runtime#exec(...)}); appended as the terminal hop so
     * PASS means taint reached that call's arguments.
     */
    public TaintResult verify(List<String> methodChainIds, String sinkApiId) {
        TaintSink sink = new TaintSink();
        List<MethodRef> refs = new ArrayList<>();
        for (String id : methodChainIds) {
            MethodRef r = MethodRef.parse(id);
            if (r == null) {
                sink.emit("unparseable id: " + id);
                return new TaintResult(false, sink.getLog());
            }
            refs.add(r);
        }
        MethodRef sinkApi = MethodRef.parse(sinkApiId);
        if (sinkApi != null) {
            refs.add(sinkApi);
        }
        if (refs.size() < 2) {
            sink.emit("chain too short to verify (need >= 1 hop)");
            return new TaintResult(false, sink.getLog());
        }

        TaintTransfer entry = new TaintTransfer(); // empty: taint originates from source calls inside
        for (int i = 0; i < refs.size() - 1; i++) {
            MethodRef cur = refs.get(i);
            MethodRef next = refs.get(i + 1);
            Path classFile = workDir.resolve(cur.owner + ".class");
            if (!Files.exists(classFile)) {
                sink.emit("missing class file for " + cur.owner + " (hop " + i + ")");
                return new TaintResult(false, sink.getLog());
            }
            TaintTransfer exit = new TaintTransfer();
            try {
                byte[] bytes = Files.readAllBytes(classFile);
                TaintClassVisitor tcv = new TaintClassVisitor(
                        entry, cur, next, exit, sanitizer, propagation, catalog, sink);
                new ClassReader(bytes).accept(tcv, ClassReader.EXPAND_FRAMES);
                exit = tcv.getExit();
            } catch (Throwable t) {
                // robust: complex/old bytecode the simulator can't handle -> inconclusive (fail)
                sink.emit("analysis error at " + cur + ": " + t);
                return new TaintResult(false, sink.getLog());
            }
            if (exit == null || !exit.hasTaint()) {
                sink.emit("taint did not reach next at hop " + i + " (" + cur + ")");
                return new TaintResult(false, sink.getLog(), sink.getSanitizers());
            }
            entry = exit;
        }
        sink.emit("taint reached the sink");
        return new TaintResult(true, sink.getLog(), sink.getSanitizers());
    }
}
