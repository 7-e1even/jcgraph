/*
 * Adapted from jar-analyzer (https://github.com/jar-analyzer/jar-analyzer)
 * Original: GPLv3 License, Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 * Ported into jcgraph (gson instead of fastjson2); remains under GPLv3.
 */
package io.jcgraph.taint;

/**
 * One taint propagation rule for library calls that move taint into/out of an
 * object/container (e.g. {@code StringBuilder.append(arg) -> this,ret}).
 *
 * <p>{@code from}: trigger condition — {@code any} (any tainted arg), {@code this}
 * (tainted receiver), or an integer locals index. {@code to}: targets — {@code ret}
 * (taint return), {@code this} (taint receiver), or an integer arg slot. Both are
 * comma-separated. {@code methodDesc == "*"} matches all overloads.</p>
 */
public class PropagationRule {

    public static final String FROM_ANY = "any";
    public static final String FROM_THIS = "this";
    public static final String TO_RET = "ret";
    public static final String TO_THIS = "this";
    public static final String DESC_ANY = "*";

    private String className;
    private String methodName;
    private String methodDesc;
    private String from;
    private String to;

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getMethodDesc() {
        return methodDesc;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    @Override
    public String toString() {
        return "PropagationRule{" + className + '.' + methodName + methodDesc
                + ", from=" + from + ", to=" + to + '}';
    }
}
