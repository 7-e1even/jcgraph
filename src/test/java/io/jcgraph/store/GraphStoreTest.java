package io.jcgraph.store;

import io.jcgraph.model.Edge;
import io.jcgraph.model.EdgeKind;
import io.jcgraph.model.Ids;
import io.jcgraph.model.Node;
import io.jcgraph.model.NodeKind;
import io.jcgraph.model.Origin;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GraphStoreTest {

    @Test
    public void findByNameUsesFtsAcrossOwnerAndName() throws Exception {
        Path dir = Files.createTempDirectory("jcgraph-store-test");
        Path source = dir.resolve("Demo.java");
        Files.write(source, Collections.singletonList("package demo; class Demo { void run() {} }"),
                StandardCharsets.UTF_8);

        try (GraphStore store = GraphStore.open(dir.resolve("index.db").toString())) {
            store.clearGraph();
            store.writeBatch(sampleNodes(source), sampleEdges(),
                    Collections.singletonList(new String[]{source.toString(), "source", null, "java"}));

            List<Node> hits = store.findByName("demo run");

            assertTrue(containsId(hits, Ids.method("demo/Demo", "run", "()V")));
        }
    }

    @Test
    public void changedFilesDetectsEditedIndexedFile() throws Exception {
        Path dir = Files.createTempDirectory("jcgraph-changed-test");
        Path source = dir.resolve("Demo.java");
        Files.write(source, Collections.singletonList("class Demo { void run() {} }"),
                StandardCharsets.UTF_8);

        try (GraphStore store = GraphStore.open(dir.resolve("index.db").toString())) {
            store.clearGraph();
            store.writeBatch(sampleNodes(source), sampleEdges(),
                    Collections.singletonList(new String[]{source.toString(), "source", null, "java"}));
            assertEquals(0, store.changedFiles(10).size());

            Files.write(source, Collections.singletonList("class Demo { void run() { int x = 1; } }"),
                    StandardCharsets.UTF_8);

            List<GraphStore.FileRecord> changed = store.changedFiles(10);
            assertEquals(1, changed.size());
            assertEquals("changed", changed.get(0).errors);
        }
    }

    private static List<Node> sampleNodes(Path source) {
        Node cls = Node.of(Ids.clazz("demo/Demo"), NodeKind.CLASS, "Demo");
        cls.filePath = source.toString();
        cls.startLine = 1;
        cls.endLine = 1;

        Node method = Node.of(Ids.method("demo/Demo", "run", "()V"), NodeKind.METHOD, "run");
        method.owner = "demo/Demo";
        method.descriptor = "()V";
        method.filePath = source.toString();
        method.startLine = 1;
        method.endLine = 1;

        return Arrays.asList(cls, method);
    }

    private static List<Edge> sampleEdges() {
        return Collections.singletonList(Edge.of(
                Ids.clazz("demo/Demo"),
                Ids.method("demo/Demo", "run", "()V"),
                EdgeKind.CONTAINS,
                Origin.SOURCE,
                "test"));
    }

    private static boolean containsId(List<Node> nodes, String id) {
        for (Node n : nodes) {
            if (id.equals(n.id)) {
                return true;
            }
        }
        return false;
    }
}
