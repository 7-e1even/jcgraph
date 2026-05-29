# 当前实现缺口

本文只记录当前代码实现本身还不完整或容易产生错误结论的地方，不覆盖许可证、CI、发布包等外围交付事项。

## 优先级较高

### 1. 源码输入的调用图基本缺失

`src/main/java/io/jcgraph/extract/SourceExtractor.java` 目前只从 `.java` 生成类、方法、字段节点，以及 `CONTAINS` / `EXTENDS` / `IMPLEMENTS` 结构边，不生成 `CALLS` 边。

影响：
- 只索引源码目录时，`callers` / `callees` / `sinks` / `trace` / `taint` 的结果会很弱。
- 当前调用图事实来源仍然是字节码输入，源码输入主要提供结构、位置和方法体。

建议：
- 引入 JavaParser SymbolSolver，解析源码方法调用目标。
- 对无法解析的调用显式标记为 unresolved，避免用户误以为“没有调用”。

### 2. 源码方法描述符不可靠

`SourceExtractor.typeToDesc` 和 `resolve` 依赖字符串猜 JVM descriptor，缺少完整类型解析。

容易出错的场景：
- `String` 未解析成 `java/lang/String`。
- 泛型、类型参数、通配符、复杂数组类型。
- 内部类、静态导入、同包/跨包同名类型。
- 源码节点和字节码节点 descriptor 对不上，导致无法精确合并。

建议：
- 用 SymbolSolver 生成真实类型信息。
- 在无法确定 descriptor 时，保留 best-effort 结果但标注 provenance 或 confidence。

### 3. 方法体定位会撞重载

`Materializer` 和 `ContentResolver` 当前用“方法名 + 参数个数”定位反编译后的方法范围。

影响：
- 同名、同参数个数、不同参数类型的方法会互相覆盖或定位错。
- `method` / `outline` / MCP 的 `jcg_method` 可能返回错误方法体。

建议：
- enrich 时使用完整 descriptor 匹配。
- 如果反编译源码无法恢复完整类型，至少在同名同参数个数出现多候选时返回歧义提示，而不是静默选一个。

### 4. `taint` 有隐藏漏报点

`SecurityService.taint` 里存在硬编码搜索限制：`maxDepth=8`、每个 sink 最多 3 条路径、总预算 600 条 chain。

影响：
- 大项目里可能因为预算截断而漏掉真实 flow。
- 输出里目前没有明确提示“结果已被预算限制”。

另外，`dfs` 只有在 `path.size() > 1` 时才把 source-reading method 当入口。若同一个方法里读取 source 并调用 sink，且它还有 caller，最直接的数据流可能不被优先验证。

建议：
- 输出 checked / skipped / budget-exhausted 信息。
- 对 sink method 自身就是 source-reading method 的情况，优先验证单方法 flow。
- 将深度、每 sink 路径数、总预算暴露为参数。

## 中等优先级

### 5. `grep` 的字符串表查询不是严格 literal

`GraphStore.grepStrings` 使用 SQL `LIKE`，没有转义 `%` 和 `_`。

影响：
- 搜 `%`、`_` 等字符时会被当成通配符。
- 字符串表查询和文件内容扫描的 literal contains 行为不一致。

建议：
- 对 `LIKE` pattern 做 `%` / `_` / `\` 转义，并加 `ESCAPE '\'`。
- 或者改为 SQLite `instr(value, ?)` 实现真正 literal contains。

### 6. 精确 `M:` id 没校验存在性

`NavigationService.resolveMethodIds` 和 `SecurityService.resolveMethodIds` 遇到 `M:` 开头的输入会直接返回，不检查数据库里是否存在该节点。

影响：
- 传入不存在的 `M:` id 时，可能得到“0 callers/callees”或空 trace。
- 用户容易把“不存在”误读成“存在但没有调用关系”。

建议：
- 对精确 id 调 `store.getNode` 校验。
- 不存在时返回 `node not found`。

### 7. `sync/status` 对新增文件检测弱

`GraphStore.changedFiles` 只遍历 `files` 表里已有路径；`inputChangedSinceIndex` 只看输入根路径 mtime。

影响：
- 新增的深层 `.java` / `.class` 文件可能漏检。
- 某些文件系统或目录 mtime 场景下，`status` 可能显示不 stale。
- 当前 `sync` 仍是“检测到变化后整库重建”，不是真正增量索引。

建议：
- 对输入根目录重新做轻量 file inventory，与 `files` 表 diff。
- 明确区分 `changed`、`missing`、`added`。
- 后续再做按文件级别的增量更新。

## 较低优先级但会影响分析质量

### 8. 重复 class 静默丢弃

`FileCollector` 用 internal class name 去重，多个 jar 中同名类只保留第一个。

影响：
- fat jar / 多依赖版本冲突时会丢掉后续 class。
- 安全分析会失去 provenance，无法判断实际运行时可能加载的是哪个版本。

建议：
- 保留重复 class 的 provenance 信息。
- 至少在 `status` / `overview` 中报告 duplicate class 数量和来源。

### 9. MCP 输出截断缺少结构化提示

`McpServer.truncate` 会把工具输出截到 15000 字符，只追加纯文本 `... (output truncated)`。

影响：
- 调用方无法结构化判断结果是否完整。
- 对 `jcg_source` / `jcg_grep` / `jcg_sinks` 这类大输出工具尤其明显。

建议：
- 在文本里带上更明确的 next action，例如 refine query 或使用 outline/method。
- 如果 MCP 客户端支持，可在返回结果中增加 metadata 标记 truncated。

## 建议修复顺序

1. 修 `taint` 的 source-sink 同方法验证和预算提示。
2. 修 `grepStrings` literal 匹配。
3. 给精确 `M:` id 增加存在性校验。
4. 改方法定位逻辑，减少重载误匹配。
5. 增强 `sync/status` 的 added/missing/changed 文件检测。
6. 再投入较大的 SymbolSolver 工作，补源码调用边和准确 descriptor。
