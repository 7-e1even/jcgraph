package io.jcgraph.model;

import java.util.ArrayList;
import java.util.List;

/** Small helpers for JVM method descriptors. */
public final class Descriptors {
    private Descriptors() {
    }

    /** Count the parameters in a method descriptor like {@code (ILjava/lang/String;[I)V}. */
    public static int paramCount(String descriptor) {
        return paramTypes(descriptor).size();
    }

    /** Return the raw param type descriptors of a method descriptor. */
    public static List<String> paramTypes(String descriptor) {
        List<String> out = new ArrayList<>();
        if (descriptor == null) {
            return out;
        }
        int i = descriptor.indexOf('(');
        int end = descriptor.indexOf(')');
        if (i < 0 || end < 0 || end < i) {
            return out;
        }
        i++;
        while (i < end) {
            int start = i;
            while (i < end && descriptor.charAt(i) == '[') {
                i++;
            }
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int semi = descriptor.indexOf(';', i);
                i = semi + 1;
            } else {
                i++;
            }
            out.add(descriptor.substring(start, i));
        }
        return out;
    }
}
