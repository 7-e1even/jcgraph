package io.jcgraph.extract;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import io.jcgraph.collect.Collected;
import io.jcgraph.model.Descriptors;
import io.jcgraph.model.Edge;
import io.jcgraph.model.EdgeKind;
import io.jcgraph.model.Ids;
import io.jcgraph.model.Node;
import io.jcgraph.model.NodeKind;
import io.jcgraph.model.Origin;
import io.jcgraph.model.SourceDescriptors;
import io.jcgraph.security.EntryRules;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Source frontend (JavaParser). Produces nodes with exact positions and readable
 * bodies plus structural edges (CONTAINS/EXTENDS/IMPLEMENTS) and best-effort
 * CALLS / REFERENCES edges for method invocations.
 *
 * <p>This is intentionally not a full {@code JavaSymbolSolver}: it resolves the
 * easy cases (same-class calls, constructor calls, static calls through an
 * imported class name) and explicitly marks the rest with a {@link EdgeKind#REFERENCES}
 * edge to a synthetic {@code M:?#name} target so downstream tools can tell the
 * difference between "no call here" and "we couldn't resolve the receiver".</p>
 */
public class SourceExtractor {

    public final List<Node> nodes = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();

    private String pkg = "";
    private final Map<String, String> imports = new HashMap<>();
    /** Helper resolver derived from the current compilation unit. */
    private SourceDescriptors descriptors;

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
        cu.getImports().forEach(imp -> {
            if (!imp.isAsterisk() && !imp.isStatic()) {
                String fqn = imp.getNameAsString();
                int dot = fqn.lastIndexOf('.');
                imports.put(dot >= 0 ? fqn.substring(dot + 1) : fqn, fqn);
            }
        });
        descriptors = SourceDescriptors.of(cu);
        for (TypeDeclaration<?> td : cu.getTypes()) {
            visitType(td, null, filePath);
        }
    }

    private void visitType(TypeDeclaration<?> td, String enclosingInternal, String filePath) {
        String name = td.getNameAsString();
        String internal = enclosingInternal == null
                ? (pkg.isEmpty() ? name : pkg.replace('.', '/') + "/" + name)
                : enclosingInternal + "$" + name;

        // Class-level entry kind from annotations like @RestController / @Controller;
        // propagated to public instance methods that lack a more specific tag.
        String classEntryKind = null;
        for (AnnotationExpr ann : td.getAnnotations()) {
            String tag = EntryRules.fromClassAnnotation(annotationDescriptor(ann));
            if (tag != null) {
                classEntryKind = tag;
                break;
            }
        }

        NodeKind kind = NodeKind.CLASS;
        if (td.isEnumDeclaration()) {
            kind = NodeKind.ENUM;
        } else if (td.isAnnotationDeclaration()) {
            kind = NodeKind.ANNOTATION;
        } else if (td instanceof ClassOrInterfaceDeclaration
                && ((ClassOrInterfaceDeclaration) td).isInterface()) {
            kind = NodeKind.INTERFACE;
        }

        Node cn = Node.of(Ids.clazz(internal), kind, name);
        cn.filePath = filePath;
        setRange(cn, td);
        nodes.add(cn);

        if (td instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) td;
            cid.getExtendedTypes().forEach(t -> edges.add(Edge.of(Ids.clazz(internal),
                    Ids.clazz(descriptors.resolveInternal(t.getNameAsString())),
                    EdgeKind.EXTENDS, Origin.SOURCE, "source-name")));
            cid.getImplementedTypes().forEach(t -> edges.add(Edge.of(Ids.clazz(internal),
                    Ids.clazz(descriptors.resolveInternal(t.getNameAsString())),
                    EdgeKind.IMPLEMENTS, Origin.SOURCE, "source-name")));
        }

        // Build a name+arity -> descriptor index of this class's own methods so
        // unscoped calls can resolve to the exact id rather than guessing.
        Map<String, String> selfMethodDescriptors = new HashMap<>();
        Map<String, Integer> selfMethodAmbiguity = new HashMap<>();
        for (BodyDeclaration<?> member : td.getMembers()) {
            if (member instanceof MethodDeclaration) {
                MethodDeclaration md = (MethodDeclaration) member;
                String key = md.getNameAsString() + "#" + md.getParameters().size();
                selfMethodDescriptors.put(key,
                        descriptors.methodDescriptor(md.getParameters(), md.getType()));
                selfMethodAmbiguity.merge(key, 1, Integer::sum);
            }
        }

        for (MethodDeclaration md : td.getMethods()) {
            String desc = descriptors.methodDescriptor(md.getParameters(), md.getType());
            String id = Ids.method(internal, md.getNameAsString(), desc);
            Node mn = Node.of(id, NodeKind.METHOD, md.getNameAsString());
            mn.owner = internal;
            mn.descriptor = desc;
            mn.filePath = filePath;
            mn.entryKind = classifyEntry(md, desc, classEntryKind);
            setRange(mn, md);
            nodes.add(mn);
            edges.add(Edge.of(Ids.clazz(internal), id, EdgeKind.CONTAINS, Origin.SOURCE, "source"));
            collectCallEdges(md, internal, id, selfMethodDescriptors, selfMethodAmbiguity);
        }

        for (ConstructorDeclaration cd : td.getConstructors()) {
            String desc = descriptors.methodDescriptor(cd.getParameters(), null);
            String id = Ids.method(internal, "<init>", desc);
            Node mn = Node.of(id, NodeKind.METHOD, "<init>");
            mn.owner = internal;
            mn.descriptor = desc;
            mn.filePath = filePath;
            setRange(mn, cd);
            nodes.add(mn);
            edges.add(Edge.of(Ids.clazz(internal), id, EdgeKind.CONTAINS, Origin.SOURCE, "source"));
            collectCallEdges(cd, internal, id, selfMethodDescriptors, selfMethodAmbiguity);
        }

        for (FieldDeclaration fd : td.getFields()) {
            for (VariableDeclarator var : fd.getVariables()) {
                String id = Ids.field(internal, var.getNameAsString());
                Node fn = Node.of(id, NodeKind.FIELD, var.getNameAsString());
                fn.owner = internal;
                fn.descriptor = descriptors.typeDescriptor(var.getType());
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

    /**
     * Best-effort CALLS / REFERENCES extraction. Unresolved targets land on a
     * synthetic {@code M:?#name} id with a REFERENCES edge, so an analyst can
     * tell "no call here" apart from "we couldn't resolve the receiver".
     */
    private void collectCallEdges(com.github.javaparser.ast.Node body,
                                  String ownerInternal, String callerId,
                                  Map<String, String> selfDescriptors,
                                  Map<String, Integer> selfAmbiguity) {
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            int line = call.getRange().map(r -> r.begin.line).orElse(0);
            String mname = call.getNameAsString();
            int arity = call.getArguments().size();
            String key = mname + "#" + arity;
            Expression scope = call.getScope().orElse(null);

            String targetOwner = null;
            String provenance = null;
            if (scope == null || scope instanceof ThisExpr) {
                String desc = selfDescriptors.get(key);
                if (desc != null && selfAmbiguity.getOrDefault(key, 0) == 1) {
                    targetOwner = ownerInternal;
                    provenance = "source-self";
                    edges.add(Edge.call(callerId,
                            Ids.method(targetOwner, mname, desc), line, provenance, EdgeKind.CALLS));
                    continue;
                }
            } else if (scope instanceof NameExpr) {
                // Static call through a type name. Try imported FQN first, then a
                // best-effort same-package class lookup when the name looks like a
                // type (capitalized first letter). Field/local-named lowercase
                // receivers fall through to REFERENCES.
                String scopeName = ((NameExpr) scope).getNameAsString();
                String fqn = imports.get(scopeName);
                if (fqn != null) {
                    targetOwner = fqn.replace('.', '/');
                    provenance = "source-static-import";
                } else if (!scopeName.isEmpty() && Character.isUpperCase(scopeName.charAt(0))) {
                    targetOwner = descriptors.resolveInternal(scopeName);
                    provenance = "source-static-best-effort";
                }
            }
            if (targetOwner == null && scope instanceof SuperExpr) {
                // We don't track the supertype name here without a symbol solver.
                provenance = "source-super-unresolved";
            }
            String synthDesc = unresolvedDescriptor(arity);
            String target = targetOwner != null
                    ? Ids.method(targetOwner, mname, synthDesc)
                    : "M:?#" + mname + synthDesc;
            EdgeKind kind = targetOwner != null ? EdgeKind.CALLS : EdgeKind.REFERENCES;
            edges.add(Edge.call(callerId, target, line,
                    provenance != null ? provenance : "source-unresolved", kind));
        }
        for (ObjectCreationExpr ctor : body.findAll(ObjectCreationExpr.class)) {
            int line = ctor.getRange().map(r -> r.begin.line).orElse(0);
            String typeName = ctor.getType().getNameAsString();
            String targetOwner = descriptors.resolveInternal(typeName);
            int arity = ctor.getArguments().size();
            String target = Ids.method(targetOwner, "<init>", unresolvedDescriptor(arity));
            edges.add(Edge.call(callerId, target, line, "source-ctor", EdgeKind.CALLS));
        }
    }

    /**
     * Synthetic descriptor used when source-side type resolution can't pin
     * argument/return types. Encodes only the arity so the placeholder is unique
     * per (name, arity); the {@code ?} payload signals "unresolved" at a glance.
     */
    private static String unresolvedDescriptor(int arity) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < arity; i++) {
            sb.append("L?;");
        }
        sb.append(")?");
        return sb.toString();
    }

    private void setRange(Node n, com.github.javaparser.ast.Node decl) {
        decl.getRange().ifPresent(r -> {
            n.startLine = r.begin.line;
            n.endLine = r.end.line;
        });
    }

    /** {@code @RestController} -> {@code Lorg/springframework/web/bind/annotation/RestController;}. */
    private String annotationDescriptor(AnnotationExpr ann) {
        return "L" + descriptors.resolveInternal(ann.getName().asString()) + ";";
    }

    /** Method-level entry tag, falling back to class-level for public instance methods. */
    private String classifyEntry(MethodDeclaration md, String descriptor, String classEntryKind) {
        int access = 0;
        if (md.isStatic()) access |= 0x0008;
        if (md.isPublic()) access |= 0x0001;
        String entry = EntryRules.fromMain(access, md.getNameAsString(), descriptor);
        if (entry != null) {
            return entry;
        }
        for (AnnotationExpr ann : md.getAnnotations()) {
            String tag = EntryRules.fromMethodAnnotation(annotationDescriptor(ann));
            if (tag != null) {
                return tag;
            }
        }
        if (classEntryKind != null && md.isPublic() && !md.isStatic()) {
            return classEntryKind;
        }
        return null;
    }

    /**
     * Second pass: after all source files have been extracted, rewrite the
     * placeholder descriptors on cross-class CALLS edges using the real
     * (owner, name, arity) → descriptor table we now have from {@link #nodes}.
     *
     * <p>Without this pass a project-internal static call like
     * {@code MyHelper.foo(x)} stays as {@code M:pkg/MyHelper#foo(L?;)?} and
     * never unifies with the indexed {@code foo(Ljava/lang/String;)V}, so the
     * agent sees {@code [external/unresolved]} for code it can actually open.
     * Ambiguous arity (multiple overloads) is left untouched.</p>
     */
    public void linkCalls() {
        Map<String, String> uniqueDesc = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Node n : nodes) {
            if (n.kind != NodeKind.METHOD || n.owner == null || n.descriptor == null || n.name == null) {
                continue;
            }
            int arity = Descriptors.paramCount(n.descriptor);
            String key = n.owner + "#" + n.name + "#" + arity;
            uniqueDesc.put(key, n.descriptor);
            counts.merge(key, 1, Integer::sum);
        }
        int rewritten = 0;
        for (Edge e : edges) {
            if (e.kind != EdgeKind.CALLS || e.target == null || !e.target.startsWith("M:")) {
                continue;
            }
            if (!e.target.endsWith(")?") && !e.target.contains("(L?;")) {
                continue; // already a real descriptor
            }
            int hash = e.target.indexOf('#');
            int paren = e.target.indexOf('(', hash + 1);
            if (hash < 0 || paren < 0) {
                continue;
            }
            String owner = e.target.substring(2, hash);
            if ("?".equals(owner)) {
                continue; // receiver type unresolved -> nothing to look up
            }
            String name = e.target.substring(hash + 1, paren);
            int arity = unresolvedArity(e.target.substring(paren));
            String key = owner + "#" + name + "#" + arity;
            Integer c = counts.get(key);
            if (c != null && c == 1) {
                e.target = Ids.method(owner, name, uniqueDesc.get(key));
                e.provenance = "source-resolved";
                rewritten++;
            }
        }
        if (rewritten > 0) {
            System.out.println("[source] linked " + rewritten
                    + " cross-class CALLS edges to precise descriptors");
        }
    }

    /** Count {@code L?;} occurrences inside the parens of a synthetic descriptor. */
    private static int unresolvedArity(String descTail) {
        int close = descTail.indexOf(')');
        if (close < 0) {
            return 0;
        }
        int n = 0, i = 1; // skip '('
        while (i < close) {
            if (i + 3 <= close && descTail.charAt(i) == 'L'
                    && descTail.charAt(i + 1) == '?' && descTail.charAt(i + 2) == ';') {
                n++;
                i += 3;
            } else {
                i++;
            }
        }
        return n;
    }
}
