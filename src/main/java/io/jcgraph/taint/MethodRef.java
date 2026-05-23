package io.jcgraph.taint;

/**
 * A method coordinate (owner internal name + name + JVM descriptor), parsed from
 * a jcgraph method id {@code M:<owner>#<name><desc>}. Replaces jar-analyzer's
 * MethodReference.Handle so the taint engine has no dependency on that codebase.
 */
public final class MethodRef {
    public final String owner; // internal name, slashes
    public final String name;
    public final String desc;  // JVM descriptor, e.g. (Ljava/lang/String;)V

    public MethodRef(String owner, String name, String desc) {
        this.owner = owner;
        this.name = name;
        this.desc = desc;
    }

    /** Parse a jcgraph method id ({@code M:owner#name(desc)ret}); returns null if malformed. */
    public static MethodRef parse(String methodId) {
        if (methodId == null) {
            return null;
        }
        String b = methodId.startsWith("M:") ? methodId.substring(2) : methodId;
        int h = b.indexOf('#');
        if (h <= 0 || h == b.length() - 1) {
            return null;
        }
        String owner = b.substring(0, h);
        String rest = b.substring(h + 1);
        int p = rest.indexOf('(');
        if (p < 0) {
            return null;
        }
        return new MethodRef(owner, rest.substring(0, p), rest.substring(p));
    }

    @Override
    public String toString() {
        return owner + "#" + name + desc;
    }
}
