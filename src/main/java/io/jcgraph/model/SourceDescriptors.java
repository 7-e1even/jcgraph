package io.jcgraph.model;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.Type;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort source-side helper that converts JavaParser {@link Type}s into
 * JVM descriptors using the surrounding {@link CompilationUnit}'s package and
 * import map. Without a full SymbolSolver this cannot disambiguate same-name
 * types from star imports / unresolved generics, so descriptors are tagged
 * best-effort by callers (see provenance on emitted edges).
 *
 * <p>The handful of java.lang built-ins are mapped explicitly so trivial cases
 * like {@code String} / {@code Object} produce the canonical descriptor instead
 * of resolving to the current package.</p>
 */
public final class SourceDescriptors {

    private static final Set<String> JAVA_LANG = new HashSet<>();
    static {
        for (String s : new String[]{
                "Object", "String", "Integer", "Long", "Short", "Byte", "Boolean",
                "Character", "Float", "Double", "Number", "Void", "Throwable",
                "Exception", "RuntimeException", "Error", "Class", "Enum", "Iterable",
                "Comparable", "CharSequence", "StringBuilder", "StringBuffer",
                "System", "Math", "Thread", "Runnable"
        }) {
            JAVA_LANG.add(s);
        }
    }

    private final String pkg;
    private final Map<String, String> imports = new HashMap<>();

    private SourceDescriptors(String pkg) {
        this.pkg = pkg == null ? "" : pkg;
    }

    public static SourceDescriptors of(CompilationUnit cu) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        SourceDescriptors sd = new SourceDescriptors(pkg);
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && !imp.isStatic()) {
                String fqn = imp.getNameAsString();
                int dot = fqn.lastIndexOf('.');
                sd.imports.put(dot >= 0 ? fqn.substring(dot + 1) : fqn, fqn);
            }
        }
        return sd;
    }

    /** {@code (Lpkg/Foo;I)Ljava/lang/String;}-style descriptor for a method. */
    public String methodDescriptor(List<Parameter> params, Type returnType) {
        StringBuilder sb = new StringBuilder("(");
        for (Parameter p : params) {
            sb.append(typeDescriptor(p.getType()));
        }
        sb.append(')');
        sb.append(returnType == null ? "V" : typeDescriptor(returnType));
        return sb.toString();
    }

    /** JVM descriptor for a single type, recursing through arrays. */
    public String typeDescriptor(Type t) {
        if (t.isVoidType()) {
            return "V";
        }
        if (t.isArrayType()) {
            return "[" + typeDescriptor(t.asArrayType().getComponentType());
        }
        if (t.isPrimitiveType()) {
            switch (t.asString()) {
                case "int": return "I";
                case "long": return "J";
                case "double": return "D";
                case "float": return "F";
                case "boolean": return "Z";
                case "byte": return "B";
                case "char": return "C";
                case "short": return "S";
                default: return "I";
            }
        }
        return "L" + resolveInternal(t.asString()) + ";";
    }

    /** Best-effort resolution of a type name to a JVM internal name (slashes). */
    public String resolveInternal(String typeName) {
        String base = typeName == null ? "" : typeName;
        int lt = base.indexOf('<');
        if (lt >= 0) {
            base = base.substring(0, lt);
        }
        base = base.trim();
        if (base.isEmpty()) {
            return "java/lang/Object";
        }
        // already qualified?
        if (base.contains(".")) {
            return base.replace('.', '/');
        }
        // inner type: "Outer.Inner" already handled by '.' check; bare nested name -> let it through
        String fqn = imports.get(base);
        if (fqn != null) {
            return fqn.replace('.', '/');
        }
        if (JAVA_LANG.contains(base)) {
            return "java/lang/" + base;
        }
        // single-letter type variables (E, T, K, V, ...) -> Object (erasure approximation)
        if (base.length() == 1 && Character.isUpperCase(base.charAt(0))) {
            return "java/lang/Object";
        }
        if (!pkg.isEmpty()) {
            return pkg.replace('.', '/') + "/" + base;
        }
        return base;
    }
}
