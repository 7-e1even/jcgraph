package io.jcgraph.content;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import io.jcgraph.model.Descriptors;
import io.jcgraph.model.Ids;
import io.jcgraph.model.Node;
import io.jcgraph.model.SourceDescriptors;
import io.jcgraph.store.GraphStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Turns a graph node into its actual code text.
 *
 * <ul>
 *   <li>source nodes: read the .java file and slice the recorded line range</li>
 *   <li>bytecode nodes: decompile the (outer) class, then locate the method by
 *       re-parsing the decompiled text (decompiler-agnostic, robust)</li>
 * </ul>
 */
public class ContentResolver {

    private final GraphStore store;
    private final Decompiler decompiler = new Decompiler();

    public ContentResolver(GraphStore store) {
        this.store = store;
    }

    /**
     * Full source of a class. In hybrid mode every node (source or decompiled
     * bytecode) points at a .java file, so this is a uniform file read; the
     * decompile-on-demand path remains as a fallback for un-materialized dbs.
     */
    public String classSource(String classId) {
        Node n = store.getNode(classId);
        if (n == null) {
            return null;
        }
        if (n.filePath != null && n.filePath.endsWith(".java")) {
            return readFile(n.filePath);
        }
        return decompiler.decompile(outerClassFile(classId.substring(2), n.filePath));
    }

    /** Source of a single method. */
    public String methodSource(String methodId) {
        Node m = store.getNode(methodId);
        if (m == null) {
            return null;
        }
        if (m.filePath != null && m.filePath.endsWith(".java") && m.startLine > 0) {
            return slice(m.filePath, m.startLine, m.endLine);
        }
        // not materialized / unmatched -> decompile outer .class and locate by descriptor
        String classFile = outerClassFile(m.owner, m.filePath);
        String classSrc = decompiler.decompile(classFile);
        String located = locateMethod(classSrc, m.name, m.descriptor);
        if (located != null) {
            return located;
        }
        return "// (could not isolate method in decompiled output; showing full class)\n\n" + classSrc;
    }

    /** Resolve a .class file for the outermost enclosing class (never a .java). */
    private String outerClassFile(String internal, String fallback) {
        if (fallback != null && fallback.endsWith(".class")) {
            return fallback;
        }
        if (internal != null) {
            int dollar = internal.indexOf('$');
            String outer = dollar >= 0 ? internal.substring(0, dollar) : internal;
            Node outerNode = store.getNode(Ids.clazz(outer));
            if (outerNode != null && outerNode.filePath != null && outerNode.filePath.endsWith(".class")) {
                return outerNode.filePath;
            }
        }
        return fallback;
    }

    /**
     * Locate a method body in decompiled output. Matches by descriptor first; if
     * none match (e.g. source-side type resolution mismatch), falls back to
     * name+paramCount only when a single candidate exists — same-name same-paramCount
     * overloads return a clear ambiguity marker rather than silently picking one.
     */
    private static String locateMethod(String classSrc, String methodName, String descriptor) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(classSrc);
        } catch (Exception e) {
            return null;
        }
        SourceDescriptors sd = SourceDescriptors.of(cu);
        int paramCount = Descriptors.paramCount(descriptor);
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if ("<init>".equals(methodName)) {
            for (ConstructorDeclaration c : cu.findAll(ConstructorDeclaration.class)) {
                if (c.getParameters().size() != paramCount) {
                    continue;
                }
                String desc = sd.methodDescriptor(c.getParameters(), null);
                if (descriptor != null && descriptor.equals(desc)) {
                    return c.toString();
                }
                candidates.add(c.toString());
            }
        } else {
            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                if (!md.getNameAsString().equals(methodName)
                        || md.getParameters().size() != paramCount) {
                    continue;
                }
                String desc = sd.methodDescriptor(md.getParameters(), md.getType());
                if (descriptor != null && descriptor.equals(desc)) {
                    return md.toString();
                }
                candidates.add(md.toString());
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            return "// (ambiguous: " + candidates.size() + " overloads named "
                    + methodName + " take " + paramCount + " params and source-side type "
                    + "resolution could not match descriptor " + descriptor
                    + "; showing first candidate)\n\n" + candidates.get(0);
        }
        return null;
    }

    private static String readFile(String path) {
        if (path == null) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "// cannot read " + path + ": " + e;
        }
    }

    private static String slice(String path, int startLine, int endLine) {
        String content = readFile(path);
        if (content == null) {
            return null;
        }
        if (startLine <= 0 || endLine <= 0) {
            return content;
        }
        String[] lines = content.split("\n", -1);
        int from = Math.max(0, startLine - 1);
        int to = Math.min(lines.length, endLine);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
