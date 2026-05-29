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

    /**
     * A duplicate class encountered after the canonical one was already accepted.
     * Recording these preserves provenance so an analyst can see when a fat-jar
     * or multi-jar input shadowed alternate versions of the same internal name.
     */
    public static class DuplicateClass {
        public final String internalName;
        public final String container;
        public final String firstContainer;

        public DuplicateClass(String internalName, String container, String firstContainer) {
            this.internalName = internalName;
            this.container = container;
            this.firstContainer = firstContainer;
        }
    }

    public final List<ClassUnit> classes = new ArrayList<>();
    public final List<SourceUnit> sources = new ArrayList<>();
    public final List<DuplicateClass> duplicates = new ArrayList<>();
}
