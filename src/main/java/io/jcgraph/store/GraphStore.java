package io.jcgraph.store;

import io.jcgraph.model.Edge;
import io.jcgraph.model.Node;
import io.jcgraph.model.NodeKind;
import io.jcgraph.model.Origin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * SQLite-backed store for the unified graph. The graph holds coordinates and
 * relationships only; bodies live in files / the decompiler cache.
 */
public class GraphStore implements AutoCloseable {

    private final Connection conn;

    private GraphStore(Connection conn) {
        this.conn = conn;
    }

    public static GraphStore open(String dbPath) {
        try {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException ignored) {
                // JDBC 4 auto-loads via ServiceLoader; explicit load is just a safety net
            }
            // ensure the parent dir exists so e.g. ".jcgraph/index.db" can be created
            java.io.File parent = new java.io.File(dbPath).getParentFile();
            if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                throw new RuntimeException("cannot create db dir: " + parent);
            }
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
            }
            GraphStore store = new GraphStore(c);
            store.applySchema();
            return store;
        } catch (SQLException e) {
            throw new RuntimeException("cannot open db: " + dbPath, e);
        }
    }

    private void applySchema() {
        String raw = readResource("/schema.sql");
        // strip line comments so each split chunk is pure DDL (sqlite-jdbc dislikes
        // comment-only / multi-statement chunks)
        StringBuilder clean = new StringBuilder();
        for (String line : raw.split("\n")) {
            int c = line.indexOf("--");
            clean.append(c >= 0 ? line.substring(0, c) : line).append('\n');
        }
        for (String stmt : clean.toString().split(";")) {
            String s = stmt.trim();
            if (s.isEmpty()) {
                continue;
            }
            try (Statement st = conn.createStatement()) {
                st.execute(s);
            } catch (SQLException e) {
                throw new RuntimeException("cannot apply schema stmt: " + s, e);
            }
        }
    }

    private static String readResource(String path) {
        try (InputStream in = GraphStore.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + path);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException("cannot read resource: " + path, e);
        }
    }

    // ----------------------------------------------------------------- writes

    private static final String UPSERT_NODE =
            "INSERT INTO nodes(id,kind,name,owner,descriptor,signature,access,file_path,origin,start_line,end_line) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?) " +
            "ON CONFLICT(id) DO UPDATE SET " +
            "  owner=COALESCE(nodes.owner, excluded.owner), " +
            "  descriptor=COALESCE(nodes.descriptor, excluded.descriptor), " +
            "  signature=COALESCE(excluded.signature, nodes.signature), " +
            "  access=CASE WHEN nodes.access=0 THEN excluded.access ELSE nodes.access END, " +
            "  file_path=CASE WHEN excluded.origin='source' THEN excluded.file_path " +
            "                 ELSE COALESCE(nodes.file_path, excluded.file_path) END, " +
            "  origin=CASE WHEN excluded.origin='source' OR nodes.origin='source' THEN 'source' " +
            "              ELSE nodes.origin END, " +
            "  start_line=CASE WHEN excluded.start_line>0 THEN excluded.start_line ELSE nodes.start_line END, " +
            "  end_line=CASE WHEN excluded.end_line>0 THEN excluded.end_line ELSE nodes.end_line END";

    public void writeBatch(Collection<Node> nodes, Collection<Edge> edges,
                           Collection<String[]> strings, Collection<String[]> files) {
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_NODE)) {
                for (Node n : nodes) {
                    ps.setString(1, n.id);
                    ps.setString(2, n.kind.tag());
                    ps.setString(3, n.name);
                    ps.setString(4, n.owner);
                    ps.setString(5, n.descriptor);
                    ps.setString(6, n.signature);
                    ps.setInt(7, n.access);
                    ps.setString(8, n.filePath);
                    ps.setString(9, n.origin.tag());
                    ps.setInt(10, n.startLine);
                    ps.setInt(11, n.endLine);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO edges(source,target,kind,line,origin,provenance) VALUES(?,?,?,?,?,?)")) {
                for (Edge e : edges) {
                    ps.setString(1, e.source);
                    ps.setString(2, e.target);
                    ps.setString(3, e.kind.tag());
                    ps.setInt(4, e.line);
                    ps.setString(5, e.origin == null ? null : e.origin.tag());
                    ps.setString(6, e.provenance);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO strings(owner,method,value,origin) VALUES(?,?,?,?)")) {
                for (String[] s : strings) {
                    ps.setString(1, s[0]);
                    ps.setString(2, s[1]);
                    ps.setString(3, s[2]);
                    ps.setString(4, s[3]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO files(path,origin,container,language) VALUES(?,?,?,?)")) {
                for (String[] f : files) {
                    ps.setString(1, f[0]);
                    ps.setString(2, f[1]);
                    ps.setString(3, f[2]);
                    ps.setString(4, f[3]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
            throw new RuntimeException("write batch failed", e);
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    public String getMeta(String key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void putMeta(String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("putMeta failed", e);
        }
    }

    // ---------------------------------------------------------------- queries

    public Node getNode(String id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM nodes WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapNode(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Find nodes by simple name (exact, then case-insensitive substring). */
    public List<Node> findByName(String name) {
        List<Node> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM nodes WHERE name=? OR lower(name) LIKE lower(?) " +
                "ORDER BY (name=?) DESC, kind LIMIT 100")) {
            ps.setString(1, name);
            ps.setString(2, "%" + name + "%");
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapNode(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** Methods declared by a class, by the class internal name. */
    public List<Node> methodsOf(String classInternal) {
        List<Node> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM nodes WHERE owner=? AND kind='method' ORDER BY name")) {
            ps.setString(1, classInternal);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapNode(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** Nodes whose CALLS edge targets {@code targetId} (callers). */
    public List<Node> callers(String targetId) {
        return adjacent("SELECT n.* FROM edges e JOIN nodes n ON n.id=e.source " +
                "WHERE e.target=? AND e.kind='calls'", targetId);
    }

    /** Targets that {@code sourceId} CALLS (callees); may include unresolved ids. */
    public List<String> callees(String sourceId) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT target FROM edges WHERE source=? AND kind='calls'")) {
            ps.setString(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    // --------------------------------------------------- call-graph expansion

    /** Direct caller method ids (methods with a CALLS edge to {@code methodId}). */
    public List<String> callerIds(String methodId) {
        return queryIds("SELECT DISTINCT source FROM edges WHERE target=? AND kind='calls'", methodId);
    }

    /** Ancestor methods that {@code methodId} overrides (its OVERRIDES targets). */
    public List<String> overrideParentIds(String methodId) {
        return queryIds("SELECT target FROM edges WHERE source=? AND kind='overrides'", methodId);
    }

    /** Descendant methods that override {@code methodId} (its OVERRIDES sources). */
    public List<String> overrideChildIds(String methodId) {
        return queryIds("SELECT source FROM edges WHERE target=? AND kind='overrides'", methodId);
    }

    /**
     * Call sites invoking any method whose id starts with {@code targetIdPrefix}
     * (e.g. {@code M:java/lang/Runtime#exec(}). Returns {callerId, targetId} pairs.
     */
    public List<String[]> callSites(String targetIdPrefix) {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT source, target FROM edges WHERE kind='calls' AND target LIKE ? ESCAPE '\\'")) {
            String escaped = targetIdPrefix
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            ps.setString(1, escaped + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{rs.getString(1), rs.getString(2)});
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private List<String> queryIds(String sql, String param) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private List<Node> adjacent(String sql, String id) {
        List<Node> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapNode(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** Literal grep over the constant pool / source string literals. Returns {owner, method, value}. */
    public List<String[]> grepStrings(String pattern) {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT owner, method, value FROM strings WHERE value LIKE ? LIMIT 500")) {
            ps.setString(1, "%" + pattern + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /**
     * All .java files on disk for content grep: native sources plus the decompiled
     * mirror produced by hybrid-mode materialization. Read from the {@code files}
     * table so coverage is independent of whether node positions were enriched.
     */
    public List<String> javaFiles() {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT path FROM files WHERE path LIKE '%.java'")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public int count(String table) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Node mapNode(ResultSet rs) throws SQLException {
        Node n = new Node();
        n.id = rs.getString("id");
        n.kind = NodeKind.valueOf(rs.getString("kind").toUpperCase());
        n.name = rs.getString("name");
        n.owner = rs.getString("owner");
        n.descriptor = rs.getString("descriptor");
        n.signature = rs.getString("signature");
        n.access = rs.getInt("access");
        n.filePath = rs.getString("file_path");
        n.origin = Origin.valueOf(rs.getString("origin").toUpperCase());
        n.startLine = rs.getInt("start_line");
        n.endLine = rs.getInt("end_line");
        return n;
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
