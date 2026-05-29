package io.jcgraph.collect;

import org.objectweb.asm.ClassReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Normalizes any input format into work units the extractors understand:
 * <ul>
 *   <li>jar / war / jmod / fat-jar: unzip, recurse into nested jars (WEB-INF/lib, BOOT-INF/lib, lib/)</li>
 *   <li>.class (loose or in archive): derive the JVM internal name from the bytes themselves
 *       (via ASM), so BOOT-INF/WEB-INF/classes/ prefixes need no special-casing</li>
 *   <li>.java: located directly (no copy needed when already on disk)</li>
 *   <li>directory: walked recursively</li>
 * </ul>
 * Extracted .class bytes are written under {@code workDir} so the content layer
 * can decompile them later.
 */
public class FileCollector {

    private static final int MAX_NEST_DEPTH = 6;

    private final Path workDir;
    private final Collected result = new Collected();
    /** internal name -> container it was first accepted from (for duplicate provenance). */
    private final Map<String, String> seenClasses = new HashMap<>();
    private final Set<Path> seenSources = new HashSet<>();

    public FileCollector(Path workDir) {
        this.workDir = workDir;
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new RuntimeException("cannot create work dir: " + workDir, e);
        }
    }

    public Collected collect(Path input) {
        collect(input, null, 0);
        return result;
    }

    private void collect(Path path, String container, int depth) {
        try {
            if (Files.isDirectory(path)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile).forEach(p -> collectFile(p, container, depth));
                }
            } else {
                collectFile(path, container, depth);
            }
        } catch (IOException e) {
            System.err.println("[collect] error on " + path + ": " + e);
        }
    }

    private void collectFile(Path path, String container, int depth) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".jar") || lower.endsWith(".war") || lower.endsWith(".jmod")
                    || lower.endsWith(".ear")) {
                collectArchive(path, depth);
            } else if (lower.endsWith(".class")) {
                handleClassBytes(Files.readAllBytes(path), container);
            } else if (lower.endsWith(".java")) {
                if (seenSources.add(path.toAbsolutePath())) {
                    result.sources.add(new Collected.SourceUnit(path.toAbsolutePath(), container));
                }
            }
        } catch (IOException e) {
            System.err.println("[collect] cannot read " + path + ": " + e);
        }
    }

    private void collectArchive(Path archive, int depth) {
        if (depth > MAX_NEST_DEPTH) {
            return;
        }
        String container = archive.getFileName().toString();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.contains("../") || name.contains("..\\")) {
                    continue; // zip-slip guard
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".class")) {
                    if ("module-info.class".equals(Paths.get(name).getFileName().toString())) {
                        continue;
                    }
                    handleClassBytes(readAll(zip.getInputStream(entry)), container);
                } else if (lower.endsWith(".java")) {
                    Path out = workDir.resolve("src").resolve(name);
                    writeBytes(out, readAll(zip.getInputStream(entry)));
                    if (seenSources.add(out.toAbsolutePath())) {
                        result.sources.add(new Collected.SourceUnit(out.toAbsolutePath(), container));
                    }
                } else if (lower.endsWith(".jar") || lower.endsWith(".war")) {
                    // nested jar (fat-jar / war lib) -> extract to temp and recurse
                    Path tmp = Files.createTempFile("jcg-nested-", ".jar");
                    writeBytes(tmp, readAll(zip.getInputStream(entry)));
                    collectArchive(tmp, depth + 1);
                    Files.deleteIfExists(tmp);
                }
            }
        } catch (IOException e) {
            System.err.println("[collect] cannot open archive " + archive + ": " + e);
        }
    }

    /**
     * Derive the internal name straight from the class bytes (authoritative),
     * write the bytes under workDir, and record the unit.
     */
    private void handleClassBytes(byte[] bytes, String container) {
        String internal;
        try {
            internal = new ClassReader(bytes).getClassName();
        } catch (Exception e) {
            // unsupported class version / corrupt -> skip
            return;
        }
        String firstContainer = seenClasses.get(internal);
        if (firstContainer != null || seenClasses.containsKey(internal)) {
            // Duplicate (same internal name from a different jar / nested archive).
            // Preserve provenance so an analyst can see version shadowing in fat-jars.
            result.duplicates.add(new Collected.DuplicateClass(internal, container, firstContainer));
            return;
        }
        seenClasses.put(internal, container);
        Path out = workDir.resolve(internal + ".class");
        writeBytes(out, bytes);
        result.classes.add(new Collected.ClassUnit(internal, out.toAbsolutePath(), container));
    }

    private static void writeBytes(Path out, byte[] bytes) {
        try {
            Files.createDirectories(out.getParent());
            Files.write(out, bytes);
        } catch (IOException e) {
            System.err.println("[collect] cannot write " + out + ": " + e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, is.available()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
