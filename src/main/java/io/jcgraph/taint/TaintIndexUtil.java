/*
 * Adapted from jar-analyzer (https://github.com/jar-analyzer/jar-analyzer)
 * Original: GPLv3 License, Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 * Ported into jcgraph (package only); remains under GPLv3.
 */
package io.jcgraph.taint;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Two-way mapping between a callee's "locals index" and the "offset-from-top of
 * the pre-call operand stack". Locals semantics: non-static 0=this,1..N=args;
 * static 0..N-1=args. Pre-call stack top-to-bottom is last arg ... first arg [this].
 */
final class TaintIndexUtil {

    private TaintIndexUtil() {
    }

    static int calleeArgCount(int invokeOpcode, String calleeDesc) {
        int n = Type.getArgumentTypes(calleeDesc).length;
        if (invokeOpcode != Opcodes.INVOKESTATIC) {
            n += 1;
        }
        return n;
    }

    static int localIndexToStackOffsetFromTop(int invokeOpcode, String calleeDesc, int localIndex) {
        int argCount = calleeArgCount(invokeOpcode, calleeDesc);
        if (localIndex < 0 || localIndex >= argCount) {
            return -1;
        }
        return argCount - 1 - localIndex;
    }

    static int stackOffsetFromTopToCalleeLocalIndex(int invokeOpcode, String calleeDesc, int stackOffsetFromTop) {
        int argCount = calleeArgCount(invokeOpcode, calleeDesc);
        int p = argCount - 1 - stackOffsetFromTop;
        if (p < 0 || p >= argCount) {
            return -1;
        }
        return p;
    }
}
