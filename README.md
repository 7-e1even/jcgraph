# jcgraph

**A unified Java code graph for vulnerability hunting at scale.**

jcgraph indexes JVM bytecode (jar / war / class / jmod) **and** Java source into
one queryable SQLite graph — every class, method, field, and call edge. It is
built for an AI agent to triage a large, unfamiliar codebase: locate entry
points, enumerate dangerous sink call sites, trace reachable paths, and verify
data flow on a specific chain.

## Why

Most code-analysis tools target one input form (source *or* bytecode) and one
consumer (a human in an IDE). jcgraph is built for a different shape of problem:

- **Input is messy.** A real audit target is a pile of jars — third-party libs,
  shaded deps, app code — plus maybe some source. jcgraph ingests all of it and
  converges both frontends on the same node ids.
- **Consumer is an agent.** Output is compact, structured, and re-feedable. Every
  result line leads with a stable id you can paste straight into the next query,
  and high-volume tools emit JSON.
- **Scale is large.** A ~600K-node / 1.7M-edge graph from an 82 MB jar indexes in
  ~13 min and then answers queries in milliseconds.

## Install

Three ways to get a `jcgraph` command, from least to most self-contained.

### 1. One-shot install (you have a JDK + Maven)

Builds the jar and puts `jcgraph` on your PATH so it works from any directory:

```powershell
.\install.ps1            # Windows — builds, then registers this folder on the user PATH
```
```bash
./install.sh             # macOS / Linux — builds, then adds this folder to PATH (via your shell rc)
```

Re-run anytime to update (it `git pull`s first if this is a checkout). On macOS,
`install.sh` auto-detects a JDK via `/usr/libexec/java_home` and
`/Library/Java/JavaVirtualMachines`; pass `-JavaHome <jdk>` / `--java-home <jdk>`
if none is found. Both installers register the folder on PATH (skip with
`-SkipPath` / `--skip-path`) — `install.sh` appends to the rc for your shell
(`~/.zshrc`, `~/.bash_profile`, …). Open a new terminal afterward, then:
`jcgraph index app.jar`.

### 2. Self-contained bundle (the target machine has no Java)

`dist/jcgraph-<ver>-windows-full/` ships a private JRE (currently Java 8) next to
the jar. Unzip it, add the folder to PATH, and `jcgraph` runs with **no system
Java required** — the launcher prefers the bundled `jre/` and only falls back to
system `java` if it's absent.

Produce the bundles with the packaging scripts:

```powershell
.\package.ps1            # -> dist\jcgraph-<ver>-windows-full.zip  (jar + Windows JRE, self-contained)
                         #    dist\jcgraph-<ver>-system.zip        (jar + launchers only, needs system Java)
```
```bash
./package.sh             # -> dist/jcgraph-<ver>-<os>-<arch>.tar.gz  (jlink runtime; needs JDK 11+)
./package.sh --system    # also emit a no-JRE tarball (any OS)
```

The Windows full bundle copies its runtime from a `jre/` folder in the repo root.
`package.sh` builds a minimal runtime for the **current** OS/arch with `jlink`, so
run it **on the target platform**: an Apple-Silicon Mac emits
`jcgraph-<ver>-macos-arm64.tar.gz`, an Intel Mac emits `...-macos-x86_64`, and a
bundle built on one OS/arch will not run on another. If a downloaded macOS bundle
is blocked by Gatekeeper, clear the quarantine flag once:
`xattr -dr com.apple.quarantine jcgraph-<ver>-macos-<arch>`.

### 3. Manual

No install step — call the jar directly (see **Build**):

```
java -jar target/jcgraph.jar <command> ...
```

## Build

jcgraph needs a **JDK** (not just a JRE — the build runs `javac`):

```
mvn package
```

This produces `target/jcgraph.jar`, a self-contained shaded executable jar. The
build targets **Java 8 bytecode**, so the jar runs on **Java 8+**:

```
java -jar target/jcgraph.jar <command> ...
```

> ASM 9.6 reads classfiles up to Java 21. Classes compiled for a newer version
> are skipped silently during indexing — bump the `asm.version` property and
> rebuild to support newer targets.

## Index

```
java -jar jcgraph.jar index <input> [--db <path>] [--work <dir>] [--no-materialize]
```

- `<input>` — a jar, war, jmod, `.class`, `.java`, or a directory of any mix.
- `--db` — where to write the SQLite graph (default `.jcgraph/index.db`).
- `--work` — scratch dir for decompiled sources (default `<db>.work`).
- `--no-materialize` — skip CFR decompilation (graph only, no readable bodies;
  source is then resolved by decompile-on-demand at query time).

```
java -jar jcgraph.jar sync   [--db <path>] [--work <dir>] [--no-materialize]
java -jar jcgraph.jar status [--db <path>]
```

`sync` checks whether the input changed since the last run and, if so, **rebuilds
the index in place** (it is not yet a file-level incremental update). `status`
prints the recorded input, work dir, node/edge counts, file groups, and any
duplicate-class report.

## Query

The CLI mirrors the MCP tools one-to-one. Every command takes `--db`;
high-volume commands also accept `--scope <pkg/prefix>` to cut library noise
(slashes or dots both work: `--scope com/acme` or `--scope com.acme`) and many
support `--json` for structured output.

### Triage flow

```
jcgraph overview                              # attack surface: entry kinds + sink histogram
jcgraph sinks <category> --scope com/acme     # dangerous call sites in your code
jcgraph trace <M:sink-method>                 # reverse paths from a sink up to an entry point
jcgraph taint --chain M:a,M:b --sink-api M:...   # verify data flow on one chain
```

`sinks` categories: `deserialize · exec · expr · file · jndi · redirect ·
reflect · sql · ssrf · xxe`. `sources` categories: `http · net`.

### Navigation

```
jcgraph search  <query>                 # find symbols by name (FTS5)
jcgraph def     <name>                   # symbol definition + descriptor
jcgraph node    <id|name>                # exact node details / class outline
jcgraph context <task>                   # compact context bundle for a task
jcgraph callers <method|id>              # who calls this
jcgraph callees <method|id>              # what this calls
jcgraph impact  <method|id> --depth N    # reverse blast radius
```

### Source

```
jcgraph source  <class>                  # full class source (decompiled if bytecode)
jcgraph method  <class> <name>           # one method's source
jcgraph outline <class>                  # class skeleton: method ids + line ranges
jcgraph grep    <pattern>                # literal text search over the .java mirror
jcgraph files   [--origin o] [--language l] [--limit n]   # list indexed files
```

### PR / changed-files mode

Scope any high-volume command to a review set:

```
jcgraph sinks exec --changed-files changed.txt   # only call sites in listed files
jcgraph sinks exec --since HEAD~1                 # files changed since a git ref
```

`--changed-files` reads one path per line; `--since <ref>` runs
`git diff --name-only <ref>...HEAD` in the working directory.

`--json` is honored by: `sinks · sources · taint · callers · callees · impact ·
grep`.

## MCP

```
java -jar jcgraph.jar serve [--db <path>]
```

Runs as an MCP server over stdio (newline-delimited JSON-RPC 2.0). Tools are
prefixed `jcg_` and mirror the CLI; high-volume tools accept `format: "json"`
and a `changed_files` array for PR-scoped review. Register in Claude Code
(`.mcp.json` in the project root):

```json
{
  "mcpServers": {
    "jcgraph": {
      "command": "java",
      "args": ["-jar", "/abs/path/target/jcgraph.jar", "serve", "--db", ".jcgraph/index.db"]
    }
  }
}
```

The server only **reads** an existing index — build it first with `index`. If
the `java` on the client's PATH is not Java 8+, point `command` at an absolute
JDK path.

## Schema

The graph is a SQLite database. It stores **coordinates and relationships, not
bodies** — method/class source is read from `file_path` on demand (decompiled if
the node came from bytecode). Two core tables:

- **nodes** — every class/interface/enum/annotation/method/field:
  `id, kind, name, owner, descriptor, file_path, start_line, end_line,
  entry_kind, synthetic`.
- **edges** — relationships: `source, target, kind, line, provenance` where kind
  is one of `contains | calls | extends | implements | overrides | references`.

Plus `files` (indexed-file metadata for `status`/`sync`), `meta` (index config),
`schema_versions`, and `nodes_fts` (an FTS5 virtual table over node name / owner
/ descriptor backing `search`). SQLite materializes the FTS index as a handful
of `nodes_fts_*` shadow tables — ignore them; you only ever query `nodes_fts`.

Two columns carry the security signal:

- **`entry_kind`** — `HTTP | SERVLET | FILTER | MQ | MAIN | ASYNC`, or null for
  non-entries. Set from annotations (`@RestController` / `@*Mapping`, JAX-RS,
  Kafka/Rabbit/JMS listeners, `@Scheduled`/`@Async`), from overrides
  (`HttpServlet#doGet`, `Filter#doFilter`, `Runnable#run`, `Callable#call`), and
  from `main(String[])` signatures. Both `javax.*` and `jakarta.*` are
  recognized.
- **`synthetic`** — `1` for compiler-generated members (`ACC_SYNTHETIC` /
  `ACC_BRIDGE`, plus `access$*` accessors and `val$` / `this$` capture fields).
  Sink/source/overview queries hide these by default so an agent sees only real
  code.

Node ids are deterministic and re-feedable:

- `C:<internal-name>` — a class, e.g. `C:com/acme/Foo`
- `M:<owner>#<name><descriptor>` — a method, e.g. `M:com/acme/Foo#bar(Ljava/lang/String;)V`
- `F:<owner>#<name>` — a field

Because ids are derived from descriptors (not autoincremented), the bytecode and
source frontends converge on the same row: source wins for file/positions,
bytecode fills descriptors.

## How it works

1. **Collect** (`FileCollector`) — walk the input, unpack jars/wars/jmods,
   recurse nested jars, derive each class's internal name from its bytes, and
   classify every entry. Duplicate classes across jars are detected; the first
   wins and the shadowed copies are reported in `status`.
2. **Extract** two ways into one schema:
   - `BytecodeExtractor` (ASM) — the authoritative structure: nodes, call edges,
     entry classification from annotations, synthetic flagging.
   - `SourceExtractor` (JavaParser) — the same schema from `.java`, with a
     second pass that links cross-class `calls` edges by name.
3. **Materialize** (`Materializer` + CFR) — decompile bytecode to a `.java`
   mirror so an agent can read method bodies, then re-parse to enrich node line
   ranges. The graph points at these files; `grep` scans them.
4. **Link** (`CallGraphLinker`) — resolve polymorphism into `overrides` edges so
   the call graph can be expanded through interfaces/abstract methods at query
   time (`callers`/`callees`/`trace` flow through implementations).
5. **Query** — `NavigationService` (structure) and `SecurityService`
   (sink/trace/taint) read the graph; `ContentResolver` reads bodies on demand.

## Taint

The taint analyzer is a **chain verifier, not a scanner.** Given a specific call
chain, it runs per-method abstract interpretation over the operand stack and
locals (no heap/field model) to confirm whether a tainted argument actually
reaches the sink:

```
jcgraph taint --chain M:a,M:b,M:c --sink-api M:javax/naming/Context#lookup
```

It returns `PASS` / `FAIL` plus a readable step log and any sanitizers hit. A
`FAIL` is informative — the log says *where* the flow stopped (e.g. "taint did
not reach next at hop 0"), which usually points at a heap/field crossing the
analyzer doesn't model. `FAIL` means "not proven," not "safe."

Use it to **verify a chain you already triaged** with `trace` — not to discover
flows from scratch. A legacy scan mode (`jcgraph taint <category>`) exists and is
tunable (`--depth`, `--paths`, `--budget`), but it returns ~0 verified flows on
DI/framework-heavy code where data crosses fields; `trace` + `sinks` are the
discovery primitives.

## Status

Done: call graph, source/sink/sanitizer catalog, reverse trace, chain verifier,
entry-point classification, synthetic filtering, parallel taint, JSON + scope +
changed-files filtering, MCP server.

Known limits (see [docs/IMPLEMENTATION_GAPS.md](docs/IMPLEMENTATION_GAPS.md) for
the full list): source `.java` call edges and descriptors are best-effort
without a symbol solver (the call graph's source of truth is bytecode);
decompiled-method location matches on name + arity, so same-arity overloads can
collide; `sync` rebuilds rather than incrementally updates. Heap-aware data-flow
scanning is deliberately out of scope (multi-week IFDS engineering) — the design
instead gives an agent precise, composable primitives and an honest, explainable
verifier.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the developer-facing module
map and extension recipes.

## License

(unspecified)
