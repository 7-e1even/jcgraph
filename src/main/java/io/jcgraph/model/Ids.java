package io.jcgraph.model;

/**
 * Deterministic, content-addressable node ids. Both frontends compute ids the
 * same way so a class indexed from bytecode and from source land on the same
 * row. The id is the cross-frontend key discussed in the design:
 *
 * <ul>
 *   <li>class:  {@code C:<internalName>}            e.g. {@code C:com/example/Foo}</li>
 *   <li>method: {@code M:<owner>#<name><descriptor>} e.g. {@code M:com/example/Foo#bar(Ljava/lang/String;)V}</li>
 *   <li>field:  {@code F:<owner>#<name>}            e.g. {@code F:com/example/Foo#count}</li>
 * </ul>
 */
public final class Ids {
    private Ids() {
    }

    public static String clazz(String internalName) {
        return "C:" + internalName;
    }

    public static String method(String ownerInternal, String name, String descriptor) {
        return "M:" + ownerInternal + "#" + name + descriptor;
    }

    public static String field(String ownerInternal, String name) {
        return "F:" + ownerInternal + "#" + name;
    }
}
