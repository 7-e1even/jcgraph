/*
 * Adapted from jar-analyzer (https://github.com/jar-analyzer/jar-analyzer)
 * Original: GPLv3 License, Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 * Ported into jcgraph: MethodRef/TaintSink instead of jar-analyzer types, gson
 * rules, and a new source-rule coloring case for web taint. Remains under GPLv3.
 */
package io.jcgraph.taint;

import io.jcgraph.security.RuleCatalog;
import io.jcgraph.taint.jvm.JVMRuntimeAdapter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** In-method taint interpreter for one hop of a chain. */
public class TaintMethodAdapter extends JVMRuntimeAdapter<String> {

    static final String TAINT = "TAINT";

    private final String owner;
    private final String name;
    private final String desc;

    private final TaintTransfer entry;
    private final MethodRef next;
    private final TaintTransfer exit;
    private final SanitizerRule rule;
    private final PropagationRuleSet propagation;
    private final RuleCatalog catalog; // jcgraph source rules (web taint origin)
    private final TaintSink sink;

    public TaintMethodAdapter(int api, MethodVisitor mv, String owner,
                              int access, String name, String desc,
                              TaintTransfer entry, MethodRef next, TaintTransfer exit,
                              SanitizerRule rule, PropagationRuleSet propagation,
                              RuleCatalog catalog, TaintSink sink) {
        super(api, mv, owner, access, name, desc);
        this.owner = owner;
        this.name = name;
        this.desc = desc;
        this.entry = entry;
        this.next = next;
        this.exit = exit;
        this.rule = rule;
        this.propagation = propagation;
        this.catalog = catalog;
        this.sink = sink;
    }

    @Override
    public void visitCode() {
        super.visitCode();
        for (int idx = entry.getTaintedLocals().nextSetBit(0);
             idx >= 0; idx = entry.getTaintedLocals().nextSetBit(idx + 1)) {
            if (idx < localVariables.size()) {
                localVariables.set(idx, TAINT);
            }
        }
        sink.emit("enter " + owner + "#" + name + desc + " entry=" + entry);
    }

    @Override
    @SuppressWarnings("all")
    public void visitMethodInsn(int opcode, String calleeOwner, String calleeName, String calleeDesc, boolean itf) {
        List<Set<String>> stack = this.operandStack.getList();
        Type[] argumentTypes = Type.getArgumentTypes(calleeDesc);
        int argCount = argumentTypes.length + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);

        // ===== Case 1: this is the call to the chain's next hop =====
        boolean isNextCall = next != null
                && calleeOwner.equals(next.owner) && calleeName.equals(next.name) && calleeDesc.equals(next.desc);
        if (isNextCall) {
            boolean anyMarked = false;
            if (stack.size() >= argCount) {
                for (int off = 0; off < argCount; off++) {
                    Set<String> slot = stack.get(stack.size() - 1 - off);
                    if (slot != null && slot.contains(TAINT)) {
                        int calleeLocal = TaintIndexUtil.stackOffsetFromTopToCalleeLocalIndex(opcode, calleeDesc, off);
                        if (calleeLocal >= 0) {
                            exit.markLocal(calleeLocal);
                            anyMarked = true;
                            sink.emit("reach next: tainted arg -> next locals[" + calleeLocal + "]");
                        }
                    }
                }
            }
            if (!anyMarked) {
                sink.emit("reached next call but no tainted argument");
            }
            super.visitMethodInsn(opcode, calleeOwner, calleeName, calleeDesc, itf);
            return;
        }

        // ===== Case 2: sanitizer cleanses taint =====
        if (rule != null) {
            for (Sanitizer s : rule.getRules()) {
                if (!calleeOwner.equals(s.getClassName()) || !calleeName.equals(s.getMethodName())
                        || !calleeDesc.equals(s.getMethodDesc())) {
                    continue;
                }
                if (s.getParamIndex() == Sanitizer.ALL_PARAMS) {
                    if (anyArgTainted(stack, argCount)) {
                        super.visitMethodInsn(opcode, calleeOwner, calleeName, calleeDesc, itf);
                        scrubReturnValue(calleeDesc);
                        sink.emit("sanitizer (all params): " + calleeOwner + "#" + calleeName);
                        return;
                    }
                } else {
                    int off = TaintIndexUtil.localIndexToStackOffsetFromTop(opcode, calleeDesc, s.getParamIndex());
                    if (off >= 0 && stack.size() > off) {
                        Set<String> slot = stack.get(stack.size() - 1 - off);
                        if (slot != null && slot.contains(TAINT)) {
                            super.visitMethodInsn(opcode, calleeOwner, calleeName, calleeDesc, itf);
                            scrubReturnValue(calleeDesc);
                            sink.emit("sanitizer: " + calleeOwner + "#" + calleeName);
                            return;
                        }
                    }
                }
            }
        }

        // ===== Case 2.5 (jcgraph web adaptation): a source-rule call introduces taint =====
        if (catalog != null) {
            RuleCatalog.Rule matched = catalog.match("M:" + calleeOwner + "#" + calleeName + calleeDesc);
            if (matched != null && matched.kind == RuleCatalog.Kind.SOURCE) {
                super.visitMethodInsn(opcode, calleeOwner, calleeName, calleeDesc, itf);
                taintReturnValue(calleeDesc);
                sink.emit("source: " + calleeOwner + "#" + calleeName + " -> tainted return");
                return;
            }
        }

        // ===== Case 3: fine-grained propagation rule =====
        if (propagation != null) {
            for (PropagationRule pr : propagation.getRules()) {
                if (!matchesPropagation(pr, calleeOwner, calleeName, calleeDesc)) {
                    continue;
                }
                if (!propagationFromMatched(pr, opcode, calleeDesc, stack, argCount)) {
                    continue;
                }
                super.visitMethodInsn(opcode, calleeOwner, calleeName, calleeDesc, itf);
                applyPropagationTo(pr, calleeDesc);
                sink.emit("propagate " + calleeOwner + "#" + calleeName + " from=" + pr.getFrom() + " to=" + pr.getTo());
                return;
            }
        }

        // ===== Case 4: generic propagation (any tainted arg -> tainted return) =====
        boolean propagate = anyArgTainted(stack, argCount);
        super.visitMethodInsn(opcode, calleeOwner, calleeName, calleeDesc, itf);
        if (propagate) {
            taintReturnValue(calleeDesc);
        }
    }

    @Override
    @SuppressWarnings("all")
    public void visitInvokeDynamicInsn(String idyName, String idyDesc, Handle bsm, Object... bsmArgs) {
        Type[] captured = Type.getArgumentTypes(idyDesc);
        int slotCount = 0;
        for (Type t : captured) {
            slotCount += t.getSize();
        }
        List<Set<String>> stack = this.operandStack.getList();
        boolean tainted = false;
        if (stack.size() >= slotCount) {
            for (int i = 0; i < slotCount; i++) {
                Set<String> slot = stack.get(stack.size() - 1 - i);
                if (slot != null && slot.contains(TAINT)) {
                    tainted = true;
                    break;
                }
            }
        }
        super.visitInvokeDynamicInsn(idyName, idyDesc, bsm, bsmArgs);
        if (tainted) {
            taintReturnValue(idyDesc);
        }
    }

    // -------------------------------------------------------- propagation helpers

    private boolean matchesPropagation(PropagationRule pr, String o, String n, String d) {
        if (pr == null || pr.getClassName() == null || !pr.getClassName().equals(o)) {
            return false;
        }
        String mn = pr.getMethodName();
        if (mn == null || (!"*".equals(mn) && !mn.equals(n))) {
            return false;
        }
        String dd = pr.getMethodDesc();
        return dd != null && (PropagationRule.DESC_ANY.equals(dd) || dd.equals(d));
    }

    private boolean propagationFromMatched(PropagationRule pr, int opcode, String calleeDesc,
                                           List<Set<String>> stack, int argCount) {
        String from = pr.getFrom();
        if (from == null || from.trim().isEmpty()) {
            from = PropagationRule.FROM_ANY;
        }
        for (String token : from.split(",")) {
            token = token.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (PropagationRule.FROM_ANY.equals(token)) {
                if (anyArgTainted(stack, argCount)) {
                    return true;
                }
            } else if (PropagationRule.FROM_THIS.equals(token)) {
                if (opcode == Opcodes.INVOKESTATIC) {
                    continue;
                }
                int idx = stack.size() - 1 - (argCount - 1);
                if (idx >= 0 && idx < stack.size()) {
                    Set<String> slot = stack.get(idx);
                    if (slot != null && slot.contains(TAINT)) {
                        return true;
                    }
                }
            } else {
                try {
                    int localIdx = Integer.parseInt(token);
                    int off = TaintIndexUtil.localIndexToStackOffsetFromTop(opcode, calleeDesc, localIdx);
                    if (off < 0) {
                        continue;
                    }
                    int idx = stack.size() - 1 - off;
                    if (idx >= 0 && idx < stack.size()) {
                        Set<String> slot = stack.get(idx);
                        if (slot != null && slot.contains(TAINT)) {
                            return true;
                        }
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return false;
    }

    /** Applied after super.visitMethodInsn (args already popped, return pushed). */
    private void applyPropagationTo(PropagationRule pr, String calleeDesc) {
        String to = pr.getTo();
        if (to == null || to.trim().isEmpty()) {
            return;
        }
        boolean wantRet = false, wantThis = false;
        for (String t : to.split(",")) {
            t = t.trim();
            if (PropagationRule.TO_RET.equals(t)) {
                wantRet = true;
            } else if (PropagationRule.TO_THIS.equals(t)) {
                wantThis = true;
            }
        }
        Type rt = Type.getReturnType(calleeDesc);
        if ((wantRet || wantThis) && rt.getSort() != Type.VOID) {
            taintReturnValue(calleeDesc);
        }
    }

    private boolean anyArgTainted(List<Set<String>> stack, int argCount) {
        if (stack.size() < argCount) {
            return false;
        }
        for (int i = 0; i < argCount; i++) {
            Set<String> slot = stack.get(stack.size() - 1 - i);
            if (slot != null && slot.contains(TAINT)) {
                return true;
            }
        }
        return false;
    }

    private void taintReturnValue(String calleeDesc) {
        Type returnType = Type.getReturnType(calleeDesc);
        int retSize = returnType.getSize();
        if (returnType.getSort() == Type.VOID || retSize <= 0) {
            return;
        }
        List<Set<String>> stack = this.operandStack.getList();
        if (stack.size() < retSize) {
            return;
        }
        for (int j = 0; j < retSize; j++) {
            Set<String> set = new HashSet<>();
            set.add(TAINT);
            stack.set(stack.size() - retSize + j, set);
        }
    }

    private void scrubReturnValue(String calleeDesc) {
        Type returnType = Type.getReturnType(calleeDesc);
        int retSize = returnType.getSize();
        if (returnType.getSort() == Type.VOID || retSize <= 0) {
            return;
        }
        List<Set<String>> stack = this.operandStack.getList();
        if (stack.size() < retSize) {
            return;
        }
        for (int j = 0; j < retSize; j++) {
            stack.set(stack.size() - retSize + j, new HashSet<>());
        }
    }
}
