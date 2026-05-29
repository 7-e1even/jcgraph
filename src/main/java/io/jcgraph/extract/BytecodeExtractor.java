package io.jcgraph.extract;

import io.jcgraph.collect.Collected;
import io.jcgraph.model.Edge;
import io.jcgraph.model.EdgeKind;
import io.jcgraph.model.Ids;
import io.jcgraph.model.Node;
import io.jcgraph.model.NodeKind;
import io.jcgraph.model.Origin;
import io.jcgraph.security.EntryRules;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Bytecode frontend (ASM). Produces the precise structural graph: class/method/
 * field nodes, CONTAINS/EXTENDS/IMPLEMENTS/CALLS edges, and string constants.
 */
public class BytecodeExtractor {

    private static final int API = Opcodes.ASM9;

    public final List<Node> nodes = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();

    public void extract(Collected.ClassUnit unit) {
        try {
            byte[] bytes = Files.readAllBytes(unit.classFile);
            new ClassReader(bytes).accept(new CV(unit.classFile.toString()), ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            System.err.println("[bytecode] cannot read " + unit.classFile + ": " + e);
        } catch (Exception e) {
            System.err.println("[bytecode] parse error " + unit.internalName + ": " + e);
        }
    }

    private static String simpleName(String internal) {
        int slash = internal.lastIndexOf('/');
        String s = slash >= 0 ? internal.substring(slash + 1) : internal;
        int dollar = s.lastIndexOf('$');
        return dollar >= 0 ? s.substring(dollar + 1) : s;
    }

    private static final int SYNTH_MASK = Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE;

    private final class CV extends ClassVisitor {
        private final String filePath;
        private String internal;
        /** Entry tag inherited by every method on this class (if class-level @RestController etc.). */
        private String classEntryKind;

        CV(String filePath) {
            super(API);
            this.filePath = filePath;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.internal = name;
            NodeKind kind = NodeKind.CLASS;
            if ((access & Opcodes.ACC_ANNOTATION) != 0) {
                kind = NodeKind.ANNOTATION;
            } else if ((access & Opcodes.ACC_INTERFACE) != 0) {
                kind = NodeKind.INTERFACE;
            } else if ((access & Opcodes.ACC_ENUM) != 0) {
                kind = NodeKind.ENUM;
            }
            Node n = Node.of(Ids.clazz(name), kind, simpleName(name));
            n.access = access;
            n.filePath = filePath;
            n.synthetic = (access & SYNTH_MASK) != 0 ? 1 : 0;
            nodes.add(n);

            if (superName != null && !"java/lang/Object".equals(superName)) {
                edges.add(Edge.of(Ids.clazz(name), Ids.clazz(superName),
                        EdgeKind.EXTENDS, Origin.BYTECODE, "asm"));
            }
            if (interfaces != null) {
                for (String itf : interfaces) {
                    edges.add(Edge.of(Ids.clazz(name), Ids.clazz(itf),
                            EdgeKind.IMPLEMENTS, Origin.BYTECODE, "asm"));
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            String tag = EntryRules.fromClassAnnotation(descriptor);
            if (tag != null) {
                classEntryKind = tag;
            }
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            String id = Ids.field(internal, name);
            Node n = Node.of(id, NodeKind.FIELD, name);
            n.owner = internal;
            n.descriptor = descriptor;
            n.access = access;
            n.filePath = filePath;
            n.synthetic = (access & SYNTH_MASK) != 0 ? 1
                    : (name.startsWith("val$") || name.startsWith("this$") ? 1 : 0);
            nodes.add(n);
            edges.add(Edge.of(Ids.clazz(internal), id, EdgeKind.CONTAINS, Origin.BYTECODE, "asm"));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            String id = Ids.method(internal, name, descriptor);
            Node n = Node.of(id, NodeKind.METHOD, name);
            n.owner = internal;
            n.descriptor = descriptor;
            n.access = access;
            n.filePath = filePath;
            // Initial classification: main() always wins; otherwise inherit from a
            // class-level entry annotation (e.g. @RestController). Method-level
            // annotations seen during MV.visitAnnotation may overwrite.
            String entry = EntryRules.fromMain(access, name, descriptor);
            if (entry == null && classEntryKind != null
                    && (access & Opcodes.ACC_PUBLIC) != 0
                    && (access & Opcodes.ACC_STATIC) == 0
                    && !"<init>".equals(name) && !"<clinit>".equals(name)) {
                entry = classEntryKind;
            }
            n.entryKind = entry;
            n.synthetic = (access & SYNTH_MASK) != 0 ? 1
                    : (name.startsWith("access$") ? 1 : 0);
            nodes.add(n);
            edges.add(Edge.of(Ids.clazz(internal), id, EdgeKind.CONTAINS, Origin.BYTECODE, "asm"));
            return new MV(id, n);
        }
    }

    private final class MV extends MethodVisitor {
        private final String methodId;
        private final Node methodNode;
        private boolean firstLineSet;

        MV(String methodId, Node methodNode) {
            super(API);
            this.methodId = methodId;
            this.methodNode = methodNode;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            String tag = EntryRules.fromMethodAnnotation(descriptor);
            if (tag != null) {
                // Method-level annotation is more specific than the class-level inheritance.
                methodNode.entryKind = tag;
            }
            return null;
        }

        @Override
        public void visitMethodInsn(int opcode, String mowner, String mname, String mdesc, boolean itf) {
            edges.add(Edge.of(methodId, Ids.method(mowner, mname, mdesc),
                    EdgeKind.CALLS, Origin.BYTECODE, "asm"));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor,
                                           Handle bootstrapMethodHandle, Object... bsmArgs) {
            for (Object arg : bsmArgs) {
                if (arg instanceof Handle) {
                    Handle h = (Handle) arg;
                    edges.add(Edge.of(methodId, Ids.method(h.getOwner(), h.getName(), h.getDesc()),
                            EdgeKind.CALLS, Origin.BYTECODE, "indy"));
                }
            }
        }

        @Override
        public void visitLineNumber(int line, org.objectweb.asm.Label start) {
            if (!firstLineSet) {
                methodNode.startLine = line; // original-source line hint (see ContentResolver)
                firstLineSet = true;
            }
        }
    }
}
