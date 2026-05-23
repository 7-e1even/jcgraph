/*
 * Adapted from jar-analyzer (https://github.com/jar-analyzer/jar-analyzer)
 * Original: GPLv3 License, Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 * Ported into jcgraph (package only); remains under GPLv3.
 */
package io.jcgraph.taint.jvm;

/** Snapshot of locals + operand stack captured at a jump target for merging. */
public class GotoState<T> {
    private LocalVariables<T> localVariables;
    private OperandStack<T> operandStack;

    public LocalVariables<T> getLocalVariables() {
        return localVariables;
    }

    public void setLocalVariables(LocalVariables<T> localVariables) {
        this.localVariables = localVariables;
    }

    public OperandStack<T> getOperandStack() {
        return operandStack;
    }

    public void setOperandStack(OperandStack<T> operandStack) {
        this.operandStack = operandStack;
    }
}
