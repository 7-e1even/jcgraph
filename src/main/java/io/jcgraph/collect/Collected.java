package io.jcgraph.collect;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Result of normalizing any input into bytecode + source work units. */
public class Collected {

    /** A .class extracted/located on disk, with its JVM internal name. */
    public static class ClassUnit {
        public final String internalName;
        public final Path classFile;
        public final String container; // origin jar/war/jmod, or null for loose

        public ClassUnit(String internalName, Path classFile, String container) {
            this.internalName = internalName;
            this.classFile = classFile;
            this.container = container;
        }
    }

    /** A .java file located on disk. */
    public static class SourceUnit {
        public final Path javaFile;
        public final String container;

        public SourceUnit(Path javaFile, String container) {
            this.javaFile = javaFile;
            this.container = container;
        }
    }

    public final List<ClassUnit> classes = new ArrayList<>();
    public final List<SourceUnit> sources = new ArrayList<>();
}
