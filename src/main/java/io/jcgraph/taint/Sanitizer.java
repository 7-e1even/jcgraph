/*
 * Adapted from jar-analyzer (https://github.com/jar-analyzer/jar-analyzer)
 * Original: GPLv3 License, Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 * Ported into jcgraph (gson instead of fastjson2); remains under GPLv3.
 */
package io.jcgraph.taint;

/**
 * One sanitizer rule: a call that cleanses taint. {@code paramIndex} uses
 * locals-index semantics (non-static 0=this,1..N=args); {@link #ALL_PARAMS}
 * means "any tainted argument is cleansed".
 */
public class Sanitizer {
    public static final int ALL_PARAMS = 0;

    private String className;
    private String methodName;
    private String methodDesc;
    private int paramIndex;

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getMethodDesc() {
        return methodDesc;
    }

    public int getParamIndex() {
        return paramIndex;
    }

    @Override
    public String toString() {
        return "Sanitizer{" + className + '.' + methodName + methodDesc + ", paramIndex=" + paramIndex + '}';
    }
}
