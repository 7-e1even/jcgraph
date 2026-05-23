/*
 * Adapted from jar-analyzer (https://github.com/jar-analyzer/jar-analyzer)
 * Original: GPLv3 License, Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 * Ported into jcgraph: MethodRef/TaintSink/RuleCatalog instead of jar-analyzer
 * types. Remains under GPLv3.
 */
package io.jcgraph.taint;

import io.jcgraph.security.RuleCatalog;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.JSRInlinerAdapter;

/** Drives the taint interpreter over one class to analyze the {@code cur} hop. */
public class TaintClassVisitor extends ClassVisitor {

    private static final int API = Opcodes.ASM9;

    private String className;
    private boolean iface;

    private final TaintTransfer entry;
    private final MethodRef cur;
    private final MethodRef next;
    private final TaintTransfer exit;
    private final SanitizerRule rule;
    private final PropagationRuleSet propagation;
    private final RuleCatalog catalog;
    private final TaintSink sink;

    public TaintClassVisitor(TaintTransfer entry, MethodRef cur, MethodRef next, TaintTransfer exit,
                             SanitizerRule rule, PropagationRuleSet propagation,
                             RuleCatalog catalog, TaintSink sink) {
        super(API);
        this.entry = entry;
        this.cur = cur;
        this.next = next;
        this.exit = exit;
        this.rule = rule;
        this.propagation = propagation;
        this.catalog = catalog;
        this.sink = sink;
    }

    @Override
    public void visit(int version, int access, String name, String signature,
                      String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        this.className = name;
        this.iface = (access & Opcodes.ACC_INTERFACE) != 0;

        // Interface hop has no body: project entry's tainted arg slots onto next's arg slots.
        if (this.iface && next != null) {
            int nextArgCount = Type.getArgumentTypes(next.desc).length;
            for (int i = entry.getTaintedLocals().nextSetBit(0);
                 i >= 0; i = entry.getTaintedLocals().nextSetBit(i + 1)) {
                if (i == 0) {
                    continue; // skip cur's this (receiver identity changes across interface->impl)
                }
                int curParamSeq = i - 1;
                if (curParamSeq >= 0 && curParamSeq < nextArgCount) {
                    exit.markLocal(1 + curParamSeq);
                }
            }
            if (!exit.hasTaint() && entry.hasTaint()) {
                int first = entry.getTaintedLocals().nextSetBit(0);
                if (first >= 0) {
                    exit.markLocal(1 + Math.max(0, first - 1));
                }
            }
            sink.emit("interface passthrough " + cur + " -> exit=" + exit);
        }
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc,
                                     String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
        if (this.iface || cur == null) {
            return mv;
        }
        if (name.equals(cur.name) && desc.equals(cur.desc)) {
            TaintMethodAdapter tma = new TaintMethodAdapter(API, mv, this.className, access, name, desc,
                    entry, next, exit, rule, propagation, catalog, sink);
            return new JSRInlinerAdapter(tma, access, name, desc, signature, exceptions);
        }
        return mv;
    }

    public TaintTransfer getExit() {
        return this.exit;
    }
}
