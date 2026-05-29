package io.jcgraph.store;

import io.jcgraph.model.Edge;
import io.jcgraph.model.Node;
import io.jcgraph.model.NodeKind;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SQLite-backed store for the unified graph. The graph holds coordinates and
 * relationships only; bodies live in files / the decompiler cache.
 */
public class GraphStore implements AutoCloseable {

    private final Connection conn;

    public static final class FileRecord {
        public String path;
        public String origin;
        public String container;
        public String language;
        public String contentHash;
        public long size;
        public long modifiedAt;
        public long indexedAt;
        public String errors;
    }

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
            store.assertSchemaCompatible(dbPath);
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

    /**
     * Fail fast with an actionable message when an existing DB predates the
     * current schema (e.g. it was built before the {@code synthetic} column
     * existed). Without this, {@link #applySchema()} dies on
     * {@code CREATE INDEX ... ON nodes(synthetic)} with a raw "no such column"
     * SQLite stack trace that gives the user no idea what to do.
     *
     * <p>We deliberately do NOT silently {@code ALTER TABLE ADD COLUMN} the
     * missing column in: the older index never computed synthetic flags, so
     * sink/overview/search queries (which hide synthetic members) would quietly
     * leak compiler-generated junk into an audit. Rebuilding the index is the
     * honest fix, so we point the user straight at it.
     */
    private void assertSchemaCompatible(String dbPath) {
        if (!tableExists("nodes")) {
            return; // fresh DB — applySchema() will create the table correctly
        }
        if (hasColumn("nodes", "synthetic")) {
            return; // already on the current schema
        }
        String input = null;
        try {
            input = getMeta("input");
        } catch (RuntimeException ignored) {
            // meta table missing/unreadable on a very old DB — fall back to the generic hint
        }
        String rebuild = (input != null && !input.isEmpty() && !"-".equals(input))
                ? "rebuild it:  jcgraph index " + input
                : "delete it and re-index:  jcgraph index <input>";
        throw new IllegalStateException(
                "jcgraph: index DB '" + dbPath + "' was built by an older jcgraph and is missing "
                + "schema columns (nodes.synthetic). It cannot be queried as-is - " + rebuild + ".");
    }

    private boolean tableExists(String name) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean hasColumn(String table, String column) {
        // PRAGMA args can't be bound; `table` is a fixed internal literal here.
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        } catch (SQLException e) {
            return false;
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
            "INSERT INTO nodes(id,kind,name,owner,descriptor,file_path,start_line,end_line,entry_kind,synthetic) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?) " +
            "ON CONFLICT(id) DO UPDATE SET " +
            "  owner=COALESCE(nodes.owner, excluded.owner), " +
            "  descriptor=COALESCE(nodes.descriptor, excluded.descriptor), " +
            "  file_path=COALESCE(nodes.file_path, excluded.file_path), " +
            "  start_line=CASE WHEN excluded.start_line>0 THEN excluded.start_line ELSE nodes.start_line END, " +
            "  end_line=CASE WHEN excluded.end_line>0 THEN excluded.end_line ELSE nodes.end_line END, " +
            "  entry_kind=COALESCE(excluded.entry_kind, nodes.entry_kind), " +
            "  synthetic=CASE WHEN excluded.synthetic=1 THEN 1 ELSE nodes.synthetic END";

    public void writeBatch(Collection<Node> nodes, Collection<Edge> edges,
                           Collection<String[]> files) {
        writeBatchInternal(nodes, edges, files, false);
    }

    public void replaceBatch(Collection<Node> nodes, Collection<Edge> edges,
                             Collection<String[]> files) {
        writeBatchInternal(nodes, edges, files, true);
    }

    private void writeBatchInternal(Collection<Node> nodes, Collection<Edge> edges,
                                    Collection<String[]> files,
                                    boolean clearFirst) {
        try {
            conn.setAutoCommit(false);
            if (clearFirst) {
                clearGraphTables();
            }
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_NODE)) {
                for (Node n : nodes) {
                    ps.setString(1, n.id);
                    ps.setString(2, n.kind.tag());
                    ps.setString(3, n.name);
                    ps.setString(4, n.owner);
                    ps.setString(5, n.descriptor);
                    ps.setString(6, n.filePath);
                    ps.setInt(7, n.startLine);
                    ps.setInt(8, n.endLine);
                    ps.setString(9, n.entryKind);
                    ps.setInt(10, n.synthetic);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            writeFts(nodes);
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
                    "INSERT INTO files(path,origin,container,language,content_hash,size,modified_at,indexed_at,errors) " +
                    "VALUES(?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(path) DO UPDATE SET " +
                    "  origin=excluded.origin, container=excluded.container, language=excluded.language, " +
                    "  content_hash=excluded.content_hash, size=excluded.size, " +
                    "  modified_at=excluded.modified_at, indexed_at=excluded.indexed_at, errors=excluded.errors")) {
                for (String[] f : files) {
                    FileRecord meta = fileMeta(f[0]);
                    ps.setString(1, f[0]);
                    ps.setString(2, f[1]);
                    ps.setString(3, f[2]);
                    ps.setString(4, f[3]);
                    ps.setString(5, meta.contentHash);
                    ps.setLong(6, meta.size);
                    ps.setLong(7, meta.modifiedAt);
                    ps.setLong(8, meta.indexedAt);
                    ps.setString(9, meta.errors);
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

    private void writeFts(Collection<Node> nodes) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM nodes_fts WHERE id=?");
             PreparedStatement ins = conn.prepareStatement(
                     "INSERT INTO nodes_fts(id,name,owner,descriptor) VALUES(?,?,?,?)")) {
            for (Node n : nodes) {
                del.setString(1, n.id);
                del.addBatch();
                ins.setString(1, n.id);
                ins.setString(2, n.name);
                ins.setString(3, n.owner);
                ins.setString(4, n.descriptor);
                ins.addBatch();
            }
            del.executeBatch();
            ins.executeBatch();
        }
    }

    public void clearGraph() {
        try (Statement st = conn.createStatement()) {
            clearGraphTables(st);
        } catch (SQLException e) {
            throw new RuntimeException("clear graph failed", e);
        }
    }

    private void clearGraphTables() throws SQLException {
        try (Statement st = conn.createStatement()) {
            clearGraphTables(st);
        }
    }

    private void clearGraphTables(Statement st) throws SQLException {
        st.execute("DELETE FROM nodes_fts");
        st.execute("DELETE FROM edges");
        st.execute("DELETE FROM nodes");
        st.execute("DELETE FROM files");
    }

    private static FileRecord fileMeta(String path) {
        FileRecord r = new FileRecord();
        r.path = path;
        r.indexedAt = System.currentTimeMillis();
        File f = new File(path);
        if (!f.isFile()) {
            r.size = 0;
            r.modifiedAt = 0;
            r.errors = "missing";
            return r;
        }
        r.size = f.length();
        r.modifiedAt = f.lastModified();
        try {
            r.contentHash = sha256(Files.readAllBytes(Paths.get(path)));
        } catch (IOException e) {
            r.errors = e.toString();
        }
        return r;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
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

    /** Find nodes by id/name/text. Uses FTS when possible, then falls back to LIKE. */
    public List<Node> findByName(String name) {
        List<Node> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (name.startsWith("C:") || name.startsWith("M:") || name.startsWith("F:")) {
            Node exact = getNode(name);
            if (exact != null) {
                out.add(exact);
                return out;
            }
        }
        String fts = ftsQuery(name);
        if (fts != null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT n.* FROM nodes_fts f JOIN nodes n ON n.id=f.id " +
                    "WHERE nodes_fts MATCH ? " +
                    "ORDER BY bm25(nodes_fts), (n.name=?) DESC, n.kind LIMIT 100")) {
                ps.setString(1, fts);
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Node n = mapNode(rs);
                        if (seen.add(n.id)) {
                            out.add(n);
                        }
                    }
                }
            } catch (SQLException ignored) {
                // Bad FTS syntax should not make search unusable; LIKE fallback below.
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM nodes WHERE name=? OR lower(name) LIKE lower(?) " +
                "OR lower(owner) LIKE lower(?) " +
                "OR lower(descriptor) LIKE lower(?) OR lower(id) LIKE lower(?) " +
                "ORDER BY (name=?) DESC, kind LIMIT 100")) {
            ps.setString(1, name);
            String like = "%" + name + "%";
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setString(4, like);
            ps.setString(5, like);
            ps.setString(6, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Node n = mapNode(rs);
                    if (seen.add(n.id)) {
                        out.add(n);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private static String ftsQuery(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split("[^A-Za-z0-9_$<>]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" AND ");
            }
            sb.append('"').append(p.replace("\"", "\"\"")).append('"');
        }
        return sb.length() == 0 ? null : sb.toString();
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

    public List<FileRecord> files() {
        List<FileRecord> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT path,origin,container,language,content_hash,size,modified_at,indexed_at,errors " +
                             "FROM files ORDER BY language, path")) {
            while (rs.next()) {
                out.add(mapFile(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public List<String[]> groupFilesBy(String column) {
        if (!"origin".equals(column) && !"language".equals(column)) {
            throw new IllegalArgumentException("unsupported files column: " + column);
        }
        List<String[]> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COALESCE(" + column + ",'') AS k, COUNT(*) AS c FROM files GROUP BY " + column +
                             " ORDER BY c DESC, k")) {
            while (rs.next()) {
                out.add(new String[]{rs.getString(1), String.valueOf(rs.getInt(2))});
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public List<FileRecord> changedFiles(int limit) {
        List<FileRecord> out = new ArrayList<>();
        for (FileRecord r : files()) {
            if (limit > 0 && out.size() >= limit) {
                break;
            }
            File f = new File(r.path);
            if (!f.isFile()) {
                FileRecord copy = copyFile(r);
                copy.errors = "missing";
                out.add(copy);
                continue;
            }
            if (r.contentHash == null || r.contentHash.isEmpty()) {
                continue;
            }
            try {
                String now = sha256(Files.readAllBytes(Paths.get(r.path)));
                if (!r.contentHash.equals(now)) {
                    FileRecord copy = copyFile(r);
                    copy.errors = "changed";
                    out.add(copy);
                }
            } catch (IOException e) {
                FileRecord copy = copyFile(r);
                copy.errors = e.toString();
                out.add(copy);
            }
        }
        return out;
    }

    /**
     * Walk the original input root and report .java/.class files NOT in the
     * {@code files} table. Catches new files added after the last index that
     * {@link #changedFiles} would miss (it only iterates already-indexed paths).
     *
     * <p>Only meaningful for directory inputs — jar/war inputs are immutable
     * archives so "added file" means re-running {@code index} anyway.</p>
     */
    public List<String> addedFiles(int limit) {
        List<String> out = new ArrayList<>();
        String input = getMeta("input");
        if (input == null || input.isEmpty()) {
            return out;
        }
        File root = new File(input);
        if (!root.isDirectory()) {
            return out;
        }
        Set<String> indexed = new LinkedHashSet<>();
        for (FileRecord r : files()) {
            indexed.add(r.path);
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = Files.walk(root.toPath())) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!name.endsWith(".java") && !name.endsWith(".class")) {
                    continue;
                }
                String abs = p.toAbsolutePath().toString();
                if (!indexed.contains(abs)) {
                    out.add(abs);
                    if (limit > 0 && out.size() >= limit) {
                        break;
                    }
                }
            }
        } catch (IOException ignored) {
            // walking is best-effort; partial answer is better than throwing
        }
        return out;
    }

    public boolean inputChangedSinceIndex() {
        String input = getMeta("input");
        String indexed = getMeta("indexed_at");
        if (input == null || indexed == null) {
            return false;
        }
        long indexedAt;
        try {
            indexedAt = Long.parseLong(indexed);
        } catch (NumberFormatException e) {
            return false;
        }
        File f = new File(input);
        if (!f.exists()) {
            return false;
        }
        // For an archive: lastModified is authoritative. For a directory: mtime can
        // be stable while contents change, so also check whether any indexed file
        // is missing/changed or a new .java/.class appeared under the root.
        if (f.lastModified() > indexedAt) {
            return true;
        }
        if (f.isDirectory()) {
            return !changedFiles(1).isEmpty() || !addedFiles(1).isEmpty();
        }
        return false;
    }

    public int count(String table) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Count of nodes with the given {@code kind} tag (e.g. {@code "method"}, {@code "field"}). */
    public int countNodesOfKind(String kindTag) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM nodes WHERE kind=?")) {
            ps.setString(1, kindTag);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static FileRecord mapFile(ResultSet rs) throws SQLException {
        FileRecord r = new FileRecord();
        r.path = rs.getString("path");
        r.origin = rs.getString("origin");
        r.container = rs.getString("container");
        r.language = rs.getString("language");
        r.contentHash = rs.getString("content_hash");
        r.size = rs.getLong("size");
        r.modifiedAt = rs.getLong("modified_at");
        r.indexedAt = rs.getLong("indexed_at");
        r.errors = rs.getString("errors");
        return r;
    }

    private static FileRecord copyFile(FileRecord r) {
        FileRecord c = new FileRecord();
        c.path = r.path;
        c.origin = r.origin;
        c.container = r.container;
        c.language = r.language;
        c.contentHash = r.contentHash;
        c.size = r.size;
        c.modifiedAt = r.modifiedAt;
        c.indexedAt = r.indexedAt;
        c.errors = r.errors;
        return c;
    }

    private static Node mapNode(ResultSet rs) throws SQLException {
        Node n = new Node();
        n.id = rs.getString("id");
        n.kind = NodeKind.valueOf(rs.getString("kind").toUpperCase());
        n.name = rs.getString("name");
        n.owner = rs.getString("owner");
        n.descriptor = rs.getString("descriptor");
        n.filePath = rs.getString("file_path");
        n.startLine = rs.getInt("start_line");
        n.endLine = rs.getInt("end_line");
        n.entryKind = rs.getString("entry_kind");
        n.synthetic = rs.getInt("synthetic");
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
