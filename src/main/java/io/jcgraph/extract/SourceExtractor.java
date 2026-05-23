package io.jcgraph.extract;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.Type;
import io.jcgraph.collect.Collected;
import io.jcgraph.model.Edge;
import io.jcgraph.model.EdgeKind;
import io.jcgraph.model.Ids;
import io.jcgraph.model.Node;
import io.jcgraph.model.NodeKind;
import io.jcgraph.model.Origin;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Source frontend (JavaParser). Produces nodes with exact positions and readable
 * bodies plus structural edges (CONTAINS/EXTENDS/IMPLEMENTS). Call edges are left
 * to the bytecode frontend (resolving source calls needs a symbol solver).
 */
public class SourceExtractor {

    public final List<Node> nodes = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();
    public final List<String[]> strings = new ArrayList<>();

    private String pkg = "";
    private final Map<String, String> imports = new HashMap<>();

    public void extract(Collected.SourceUnit unit) {
        String filePath = unit.javaFile.toString();
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(Files.newInputStream(unit.javaFile));
        } catch (Exception e) {
            System.err.println("[source] parse error " + filePath + ": " + e);
            return;
        }
        pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        imports.clear();
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && !imp.isStatic()) {
                String fqn = imp.getNameAsString();
                int dot = fqn.lastIndexOf('.');
                imports.put(dot >= 0 ? fqn.substring(dot + 1) : fqn, fqn);
            }
        }
        for (TypeDeclaration<?> td : cu.getTypes()) {
            visitType(td, null, filePath);
        }
    }

    private void visitType(TypeDeclaration<?> td, String enclosingInternal, String filePath) {
        String name = td.getNameAsString();
        String internal = enclosingInternal == null
                ? (pkg.isEmpty() ? name : pkg.replace('.', '/') + "/" + name)
                : enclosingInternal + "$" + name;

        NodeKind kind = NodeKind.CLASS;
        if (td.isEnumDeclaration()) {
            kind = NodeKind.ENUM;
        } else if (td.isAnnotationDeclaration()) {
            kind = NodeKind.ANNOTATION;
        } else if (td instanceof ClassOrInterfaceDeclaration
                && ((ClassOrInterfaceDeclaration) td).isInterface()) {
            kind = NodeKind.INTERFACE;
        }

        Node cn = Node.of(Ids.clazz(internal), kind, name, Origin.SOURCE);
        cn.filePath = filePath;
        setRange(cn, td);
        nodes.add(cn);

        if (td instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) td;
            cid.getExtendedTypes().forEach(t -> edges.add(Edge.of(Ids.clazz(internal),
                    Ids.clazz(resolve(t.getNameAsString())), EdgeKind.EXTENDS, Origin.SOURCE, "source-name")));
            cid.getImplementedTypes().forEach(t -> edges.add(Edge.of(Ids.clazz(internal),
                    Ids.clazz(resolve(t.getNameAsString())), EdgeKind.IMPLEMENTS, Origin.SOURCE, "source-name")));
        }

        for (MethodDeclaration md : td.getMethods()) {
            String desc = descriptor(md.getParameters(), md.getType());
            String id = Ids.method(internal, md.getNameAsString(), desc);
            Node mn = Node.of(id, NodeKind.METHOD, md.getNameAsString(), Origin.SOURCE);
            mn.owner = internal;
            mn.descriptor = desc;
            mn.signature = md.getDeclarationAsString(false, false, false);
            mn.filePath = filePath;
            setRange(mn, md);
            nodes.add(mn);
            edges.add(Edge.of(Ids.clazz(internal), id, EdgeKind.CONTAINS, Origin.SOURCE, "source"));
            md.findAll(StringLiteralExpr.class).forEach(s ->
                    strings.add(new String[]{internal, md.getNameAsString(), s.getValue(), "source"}));
        }

        for (ConstructorDeclaration cd : td.getConstructors()) {
            String desc = descriptor(cd.getParameters(), null);
            String id = Ids.method(internal, "<init>", desc);
            Node mn = Node.of(id, NodeKind.METHOD, "<init>", Origin.SOURCE);
            mn.owner = internal;
            mn.descriptor = desc;
            mn.signature = cd.getDeclarationAsString(false, false, false);
            mn.filePath = filePath;
            setRange(mn, cd);
            nodes.add(mn);
            edges.add(Edge.of(Ids.clazz(internal), id, EdgeKind.CONTAINS, Origin.SOURCE, "source"));
            cd.findAll(StringLiteralExpr.class).forEach(s ->
                    strings.add(new String[]{internal, "<init>", s.getValue(), "source"}));
        }

        for (FieldDeclaration fd : td.getFields()) {
            for (VariableDeclarator var : fd.getVariables()) {
                String id = Ids.field(internal, var.getNameAsString());
                Node fn = Node.of(id, NodeKind.FIELD, var.getNameAsString(), Origin.SOURCE);
                fn.owner = internal;
                fn.descriptor = typeToDesc(var.getType());
                fn.filePath = filePath;
                setRange(fn, fd);
                nodes.add(fn);
                edges.add(Edge.of(Ids.clazz(internal), id, EdgeKind.CONTAINS, Origin.SOURCE, "source"));
            }
        }

        // nested types
        td.getMembers().forEach(m -> {
            if (m instanceof TypeDeclaration) {
                visitType((TypeDeclaration<?>) m, internal, filePath);
            }
        });
    }

    private void setRange(Node n, com.github.javaparser.ast.Node decl) {
        decl.getRange().ifPresent(r -> {
            n.startLine = r.begin.line;
            n.endLine = r.end.line;
        });
    }

    private String descriptor(List<com.github.javaparser.ast.body.Parameter> params, Type returnType) {
        StringBuilder sb = new StringBuilder("(");
        for (com.github.javaparser.ast.body.Parameter p : params) {
            sb.append(typeToDesc(p.getType()));
        }
        sb.append(')');
        sb.append(returnType == null ? "V" : typeToDesc(returnType));
        return sb.toString();
    }

    private String typeToDesc(Type t) {
        if (t.isVoidType()) {
            return "V";
        }
        if (t.isArrayType()) {
            return "[" + typeToDesc(t.asArrayType().getComponentType());
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
        return "L" + resolve(t.asString()) + ";";
    }

    /** Best-effort resolution of a type name to a JVM internal name. */
    private String resolve(String typeName) {
        String base = typeName;
        int lt = base.indexOf('<');
        if (lt >= 0) {
            base = base.substring(0, lt);
        }
        base = base.trim();
        if (base.contains(".")) {
            return base.replace('.', '/');
        }
        String fqn = imports.get(base);
        if (fqn != null) {
            return fqn.replace('.', '/');
        }
        if (!pkg.isEmpty()) {
            return pkg.replace('.', '/') + "/" + base;
        }
        return base;
    }
}
