# jcgraph — Architecture & Development Guide

This is the developer-facing companion to `README.md` (which is usage-only).
It explains how the pieces fit, the data model, and exactly where to change things
when extending the tool.

---

## 1. Big picture

jcgraph turns **any Java input format** (jar / war / jmod / fat-jar / loose .class /
.java / directory) into **one SQLite graph**, then serves **navigation + content +
grep** on top — over a CLI and over MCP.

Two-layer separation is the core idea:

- **Graph layer** = coordinates + relationships only (`nodes`, `edges`, `strings`).
  Built by **ASM** (bytecode) and **JavaParser** (source). This is the source of truth.
- **Content layer** = the actual code text. For source it's the `.java` on disk; for
  bytecode it's a **decompiled `.java` mirror** produced at index time (hybrid mode).

```
formats ─ FileCollector ─┬─ .class ─ BytecodeExtractor (ASM) ─────────┐
                         └─ .java  ─ SourceExtractor  (JavaParser) ───┤
                                                                       ▼
                              Materializer (hybrid): .class ─CFR→ .sources/<internal>.java
                                                     re-parse → enrich node positions
                                                                       ▼
                                          GraphStore (SQLite: nodes/edges/strings/files/meta)
                                                                       ▼
                          NavigationService + ContentResolver  ──►  CLI (Main) / MCP (McpServer)
```

---

## 2. Module map

One package per responsibility under `src/main/java/io/jcgraph/`:

| Package / class | Responsibility |
|---|---|
| `model/` | Plain data + id scheme. `Node`, `Edge`, `NodeKind`, `EdgeKind`, `Origin`, `Ids`, `Descriptors`. No logic beyond id/descriptor helpers. |
| `collect/FileCollector` | **Format normalization.** Unzips jar/war/jmod, recurses nested jars, derives each class's internal name from its bytes (ASM), writes `.class` into the work dir. Emits `Collected` (class units + source units). |
| `extract/BytecodeExtractor` | **ASM frontend.** Visits each `.class` → class/method/field nodes, `contains/extends/implements/calls` edges, string constants. The authoritative graph + call graph. |
| `extract/SourceExtractor` | **JavaParser frontend.** Visits each `.java` → nodes with exact positions + structural edges. No call edges (needs a symbol solver). |
| `content/Decompiler` | CFR wrapper. Decompiles a `.class` to a Java string **in memory** (custom `OutputSinkFactory`), cached per file. The single seam to swap for Vineflower on JDK 11+. |
| `content/Materializer` | **Hybrid mode.** Decompiles every top-level bytecode class to `<work>/.sources/<internal>.java`, then re-parses to enrich bytecode node positions (filePath + line range). |
| `content/ContentResolver` | Node → code text. Uniform `.java` read+slice; falls back to decompile-on-demand + locate for un-materialized dbs. |
| `store/GraphStore` | SQLite open/schema/upsert-batch + all read queries. |
| `query/NavigationService` | search / def / callers / callees / grep — returns formatted strings. |
| `mcp/McpServer` | Stdio JSON-RPC 2.0 MCP server exposing the read API as `jcg_*` tools. |
| `Main` | CLI entry + arg parsing + the `index` orchestration. |

`src/main/resources/schema.sql` is the DB schema (copied into the jar by the build).

---

## 3. Pipelines

### Index (`Main.index`)
1. `FileCollector.collect(input)` → normalizes all formats to `{ClassUnit, SourceUnit}`,
   extracting `.class`/`.java` under the work dir.
2. `BytecodeExtractor.extract(cu)` for each class → `be.nodes / be.edges / be.strings`.
3. `SourceExtractor.extract(su)` for each source → `se.nodes / se.edges / se.strings`.
4. **Hybrid (default):** `Materializer.materialize(be.nodes)` decompiles each top-level
   class to `.sources/*.java` and mutates `be.nodes` in place (filePath + line range).
   Skip with `--no-materialize`.
5. `GraphStore.writeBatch(...)` upserts everything in one transaction.

### Query
- `GraphStore` runs the SQL; `NavigationService` formats; `ContentResolver` turns a node
  into code text; `Main` / `McpServer` are the two front doors.

---

## 4. Data model

### Schema (`schema.sql`)
- `nodes(id, kind, name, owner, descriptor, signature, access, file_path, origin, start_line, end_line)`
- `edges(source, target, kind, line, origin, provenance)` — PK `(source,target,kind)`
- `strings(id, owner, method, value, origin)` — constant-pool + source literals (cheap grep)
- `files(path, origin, container, language)` — every on-disk artifact (drives `javaFiles()`)
- `meta(key, value)` — `input`, `work_dir`, `indexed_at`

### Node ids (`Ids`) — the cross-frontend key
- class:  `C:<internalName>`            e.g. `C:com/example/Foo`
- method: `M:<owner>#<name><descriptor>` e.g. `M:com/example/Foo#bar(Ljava/lang/String;)V`
- field:  `F:<owner>#<name>`

Deterministic ids mean bytecode and source converge on the same row. `GraphStore`'s
node upsert (`ON CONFLICT(id) DO UPDATE`) merges: source wins for file/positions,
bytecode fills descriptors/access.

### NodeKind / EdgeKind
- NodeKind: `class interface enum annotation method field`
- EdgeKind: `contains calls extends implements overrides references`

Both stored lowercase in the `kind` column — **adding a kind is a new enum value, not a
new table.** This is what keeps the schema format/language-agnostic.

---

## 5. Extension recipes

**Add an input format** → `collect/FileCollector`. Add a branch in `collectFile` /
`collectArchive`. If it ultimately yields `.class` or `.java`, the rest of the pipeline
needs no change. (e.g. `.aar`, `.apk`→dex would need a dex→class step first.)

**Add a node/edge kind** → add the enum value in `model/NodeKind`/`EdgeKind`, emit it
from the relevant extractor. No schema migration.

**Richer bytecode edges** (e.g. field access, annotations-as-edges) → `extract/
BytecodeExtractor` (add visitor callbacks). For source equivalents → `SourceExtractor`.

**Source call edges / precise source descriptors** → add JavaParser **SymbolSolver** in
`SourceExtractor` (set a `TypeSolver` over the indexed classpath). Today source
descriptors are best-effort (no type resolution) — see Limits.

**Swap the decompiler** → `content/Decompiler` only. On JDK 11+ use Vineflower (same
`IResultSaver`/`OutputSinkFactory` model) and you also get the `bsm` bytecode↔source
line map, which can replace the re-parse-to-locate step in `ContentResolver`/`Materializer`.

**Add an MCP tool** → `mcp/McpServer`: add a `tool(...)` in `toolsList()` and a `case`
in `toolsCall()`. Keep the `jcg_` prefix so it never collides with the agent's built-in
grep/search/read.

**Add a CLI command** → `Main`: a `case` in the `switch` + a handler.

**New query** → `store/GraphStore` (the SQL) + `query/NavigationService` (formatting).

---

## 6. Key design decisions (and why)

- **ASM builds the graph, not the decompiler.** ASM reads any valid classfile reliably
  and gives fully-resolved call targets + exact descriptors for free. Decompile-then-parse
  is lossy, can fail, and loses call-graph precision. So bytecode → ASM for edges, always.
- **Decompile is only the content/grep layer.** Materialized `.java` makes `source`,
  `method`, and `grep` uniform across source and bytecode — without degrading the graph.
- **`jcg_` tool prefix.** `grep`/`search`/`source` are generic; bare names confuse the
  model with its built-in tools. Prefixed + "searches the index, not the filesystem".
- **ids = descriptor, not autoincrement.** Cross-frontend merge + stable references.
- **Lazy fallback retained.** `--no-materialize` + `ContentResolver` decompile-on-demand
  means content still works without the upfront decompile cost.

---

## 7. Build, run, test

```bash
mvn -q clean package     # -> target/jcgraph.jar (shaded, runnable)
java -jar target/jcgraph.jar index <path>
java -jar target/jcgraph.jar serve --mcp --db jcgraph.db
```

### ⚠️ Build gotcha (hit during development)
The `java` on PATH here is a **JRE 8** (no `javac`) — `mvn package` fails with
*"No compiler is provided ... running on a JRE rather than a JDK?"*. Point `JAVA_HOME`
at a real JDK for the build:

```bash
# JDK 17 (supports --release/target 8). JDK 25 may have dropped source 8.
export JAVA_HOME="E:/Envs/java/graalvm-ce-java17-windows-amd64-22.3.0/graalvm-ce-java17-22.3.0"
mvn -q clean package
```
The produced jar still runs fine on the JRE 8 (the project targets Java 8).

### Smoke test (no unit tests yet)
```bash
java -jar target/jcgraph.jar index target/classes --db smoke.db   # self-index
java -jar target/jcgraph.jar def decompile --db smoke.db
java -jar target/jcgraph.jar method io.jcgraph.model.Descriptors paramCount --db smoke.db
java -jar target/jcgraph.jar grep PRAGMA --db smoke.db
# MCP handshake:
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | java -jar target/jcgraph.jar serve --mcp --db smoke.db
```

---

## 8. Known limits & next steps

Limits (also in README):
- Source `.java` produces no call edges and only best-effort descriptors (no symbol
  solver). The call graph comes from bytecode.
- Overloaded methods with the same arity may collide during decompiled-method location
  (matching is name + param-count).
- Materialization decompiles every class up front (slower index, more disk).

Candidate next steps:
- **(A)** JavaParser SymbolSolver → real source call edges + precise descriptors.
- **(B)** Parallelize `Materializer` (decompilation is the bottleneck).
- **(C)** Incremental index via `files.content_hash` (only re-index changed classes).
- **(D)** Live integration test inside Tamamo as an MCP server.
