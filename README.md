# jcgraph

统一的 Java 代码图谱。把 **jar / war / class / jmod / java**(以及目录、fat-jar、嵌套
war 依赖)索引进**一个 SQLite 图**:核心两张表(`nodes`、`edges`),外加 `strings`
表做廉价的字面量 grep。在此之上提供代码导航与全文检索。

它融合了四个思路:

- **ASM** 字节码前端 → 精确的描述符 + 调用 / 继承 / 实现边(类似 *tabby*)
- **JavaParser** 源码前端 → `.java` 的精确位置 + 可读的方法体
- **CFR** 反编译器,输出直接在内存中捕获,为字节码按需提供内容(类似 *jar-analyzer*
  的 FernFlower,但兼容 Java 8)。方法体通过重新解析反编译文本来定位。
- 通用的 `(nodes, edges, kind)` schema + 读文件切片取内容(类似 *codegraph*)

## 一张图看懂设计

```
        jar/war/jmod ─解压─┐
                           ├ .class ─ ASM ──→ nodes/edges/strings(origin=bytecode,精确调用)
        .class ────────────┘
        .java ─ JavaParser ────────────────→ nodes/edges        (origin=source,精确位置)
                                                     │
   混合物化(materialize): 每个 .class ─ CFR 反编译 → <work>/.sources/<internal>.java
                          重新解析 → 把字节码节点的位置 enrich 到指向该 .java
                                                     │
                                          一个 SQLite 图(nodes/edges/strings)
                                                     │
   导航:  图 → 坐标 ──→ 读 .java + 按行号切片(统一)
   grep:  strings 表(常量池) + 扫描每个 .java(原生 + 反编译)
```

**混合模型:** ASM 始终是图的事实来源(精确描述符 + 调用边——反编译在这两点上不可靠),
而反编译出的 `.java` 镜像是一等的内容 + grep 层,使导航和检索都统一作用在 `.java` 上。
图里只存**坐标 + 关系**;方法体存在 `.java` 文件里。

## 构建

```bash
mvn -q clean package
# -> target/jcgraph.jar(shaded、可直接运行;运行需 JDK/JRE 8+)
```

> 构建需要 **JDK**(PATH 上的 `java` 可能是不带 `javac` 的 JRE)。若 `mvn package`
> 报 *"No compiler is provided"*,把 `JAVA_HOME` 指向一个 JDK(JDK 17 可用;JDK 25
> 可能已移除 `--source 8` 支持)。构建产物 jar 仍可在 JRE 8 上运行。

架构、数据模型,以及如何扩展(新增格式 / 节点类型 / MCP 工具 / 替换反编译器),
见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 使用

```bash
# 从任意输入构建索引
java -jar target/jcgraph.jar index path/to/app.jar
java -jar target/jcgraph.jar index path/to/classes-dir
java -jar target/jcgraph.jar index path/to/src        # .java 源码

# 导航
java -jar target/jcgraph.jar search  parseConfig
java -jar target/jcgraph.jar def     MyClass
java -jar target/jcgraph.jar callers 'M:com/example/Foo#bar(Ljava/lang/String;)V'
java -jar target/jcgraph.jar callees doWork
java -jar target/jcgraph.jar source  com.example.Foo      # 字节码会反编译后输出
java -jar target/jcgraph.jar method  com.example.Foo bar  # 只输出方法体
java -jar target/jcgraph.jar grep    Runtime.exec         # 常量池 + 所有 .java(含反编译)

# 漏洞挖掘(详见下文「漏洞挖掘」一节)
java -jar target/jcgraph.jar sinks                        # 危险调用点(exec/sql/反序列化/...)
java -jar target/jcgraph.jar sources                      # 不可信输入读取点
java -jar target/jcgraph.jar trace  'M:com/x/Foo#bar()V'  # 反向调用链(调用可达)
java -jar target/jcgraph.jar taint  [category]            # 污点验证过的 source->sink 数据流
```

混合模式(默认)下,`index` 会把每个字节码类反编译成 `<work>/.sources/` 下的 `.java`
镜像,于是 `grep` 统一扫描所有 `.java` 文本(原生 + 反编译),`source` / `method`
就是普通的读文件。加 `--no-materialize` 可在索引时跳过反编译;此时内容回退到按需反编译,
`grep` 只覆盖常量池 `strings` 表 + 原生源码。

所有命令都接受 `--db <path>`(默认 `.jcgraph/index.db`,这样 jcgraph 生成的一切都
收拢在单个 `.jcgraph/` 目录里,类似 `.git/`)。`index` 还接受 `--work <dir>` 指定
解压出的 `.class` 缓存目录(默认 `<db>.work`,即 `.jcgraph/index.db.work/`,反编译出的
`.java` 镜像在其 `.sources/` 子目录下)。

## 反编译加速(并行)

`index` 的物化阶段(对每个类做 CFR 反编译 + JavaParser 解析)是 CPU 密集的,且每个顶层类
彼此独立,因此默认会**按 CPU 核数并行**跑。日志会打印实际使用的线程数:

```
[materialize] decompiling 128324 classes across 12 threads (resume: reusing existing .java)
```

线程数可手动指定(优先级:系统属性 > 环境变量 > CPU 核数):

```bash
# 环境变量(能穿透 jcgraph 启动脚本)
JCGRAPH_THREADS=16 java -jar target/jcgraph.jar index path/to/app.jar

# 或系统属性
java -Djcgraph.materialize.threads=16 -jar target/jcgraph.jar index path/to/app.jar
```

## 中断续跑(增量)

反编译出的 `.java` 会**即时落盘**(原子写:先写 `.tmp` 再原子改名,中断只会留下无害的
`.tmp`,绝不会产生半截的 `.java`)。因此索引中途被 `Ctrl+C` 后,**用同一条命令、同一个
`--work` 目录重跑即可续上**:

```bash
java -jar target/jcgraph.jar index path/to/app.jar     # 跑到一半 Ctrl+C
java -jar target/jcgraph.jar index path/to/app.jar     # 再跑:已反编译的类秒过,只补没做完的
```

续跑规则:

- `.sources/` 里已存在的有效 `.java` 直接复用,**跳过最耗时的 CFR 反编译**;
- 上次反编译失败留下的占位文件(`// decompile failed...`)不会被复用,会**自动重试**;
- 结束时打印汇总,一眼看出省了多少:
  `[materialize] complete: 128324 classes (125700 reused from a previous run, 2624 freshly decompiled)`

> 注意:续跑加速的是**重跑**,而非让中断当下的数据库立即可查。写库是在整个索引流程跑完时
> **一次性提交**的,所以中断后数据库仍为空,需要再完整跑一次才能查询——但这次重跑会跳过
> 反编译这个大头(`collect` / `extract` / 写库照常,这几步相对快)。

## 漏洞挖掘(source/sink + 调用图解析)

面向「在大规模代码里做安全审计」的场景,jcgraph 在精确调用图之上提供污点式(taint)
分析的基础能力。**核心是确定性、高召回的结构分析,不依赖向量/语义近似。**

**调用图多态解析。** 原始字节码调用图记录的是调用点的**声明类型**,接口/抽象方法调用
追不到具体实现。索引时新增一个全局链接阶段,基于继承图 + 方法签名生成 `OVERRIDES` 边,
于是 `callers` / `callees` 在查询时会**自动展开**:

```bash
java -jar target/jcgraph.jar callees 'M:com/x/Engine#run(...)'
#   callees of ... (9 direct, 24 via overrides)
#   ...  com/x/Valve#invoke ...                 <- 接口目标
#   ...  com/x/StandardHostValve#invoke  [virtual impl]   <- 展开出的实现
```

`[virtual impl]`(callees 方向)和 `[virtual]`(callers 方向)标记的就是经 override 展开
得到的多态目标。这是声音的过近似(宁可多列也不漏),正是污点分析需要的方向。

**source/sink 目录。** 内置一份危险 API 清单(`src/main/resources/sinks.txt`,可用
`-Djcgraph.rules=<path>` 覆盖),覆盖命令执行、SQL、反序列化、反射、JNDI、文件、SSRF、
XXE、表达式注入等 sink,以及 `HttpServletRequest#getParameter` 等不可信输入 source。

```bash
java -jar target/jcgraph.jar sinks            # 列出所有命中的危险调用点(按分类)
java -jar target/jcgraph.jar sinks exec       # 只看某一类(exec/sql/deserialize/...)
java -jar target/jcgraph.jar sources          # 列出所有不可信输入读取点
```

**反向可达 trace(污点链)。** 从一个方法(通常是命中 sink 的方法)沿调用图**反向 BFS**
走到入口,展开过 `OVERRIDES`,并把「自身读取了不可信输入」的方法标记为 `[source]`:

```bash
java -jar target/jcgraph.jar trace 'M:com/x/CGIServlet$CGIRunner#run()V'
#   [path 1] depth 2
#     com/x/CGIServlet#doGet(HttpServletRequest, ...)  [source]
#       -> com/x/CGIServlet$CGIRunner#run()  <<< sink
```

典型工作流:`sinks` 找危险点 → 对命中方法 `trace` 看是否从不可信入口可达 → `method`/`source`
读反编译代码人工确认。trace 默认深度上限 12、路径上限 20,避免在巨型图上爆炸。

**污点验证 trace+taint。** `trace` 给的是**调用可达**的候选链(能调用到 ≠ 数据真的流过去)。
`taint` 在此之上做**数据流验证**:对每个 sink 调用点反向取候选链,逐跳用 ASM 在方法内做字节码
抽象解释(模拟操作数栈/局部变量的污点),把候选链筛成**不可信输入确实流到危险调用**的真实流。

```bash
java -jar target/jcgraph.jar taint          # 所有类别的已验证数据流
java -jar target/jcgraph.jar taint exec     # 只看某类
#   == taint: 2 verified flows ... (checked 4 chains) ==
#   [exec] (2)
#     vuln/Demo#direct  ==> java/lang/Runtime#exec               <- 方法内 getParameter->exec
#     vuln/Demo#entry -> vuln/Demo#run  ==> java/lang/Runtime#exec   <- 跨方法参数传递
```

污点引擎的能力与近似:
- **污点起点**:命中 source 规则的调用返回值(如 `getParameter()`)——贴合 web 场景;
- **传播**:方法内逐指令(拼接、赋值、分支合并),跨方法靠"调用参数→形参"载荷传递;
- **库函数传播**:`propagation.json`(~280 条:StringBuilder/String/容器/Map/Stream/反射/编解码…);
- **净化**:`sanitizer.json`(转义、`PreparedStatement#setString`、`Pattern.quote` 等)切断污点;
- **近似**:无堆/字段建模(对象字段间的传播靠库规则近似覆盖),所以 **`taint` 的 FAIL 是"未证明",不等于"安全"**。`taint` 用于把候选**降噪**(只报能证明的),`trace`/`sinks` 仍是高召回的兜底。

> 规则文件 `src/main/resources/{sinks.txt,propagation.json,sanitizer.json}` 可直接编辑扩充;
> 污点引擎改编自 [jar-analyzer](https://github.com/jar-analyzer/jar-analyzer)(见下方许可证)。

## MCP 服务

通过 MCP(stdio JSON-RPC)把只读 API 暴露给 agent。先构建索引,再让 agent 连接:

```bash
java -jar target/jcgraph.jar serve --mcp --db jcgraph.db
```

工具名都带 `jcg_` 前缀,避免和 agent 自带的 grep/search/read 冲突:导航类
`jcg_search`、`jcg_def`、`jcg_callers`、`jcg_callees`、`jcg_source`、`jcg_method`、
`jcg_grep`,以及漏洞挖掘类 `jcg_sinks`、`jcg_sources`、`jcg_trace`、`jcg_taint`。其中
`jcg_grep` 检索的是**被索引的 Java 项目**,不是本地文件系统。客户端配置示例(Claude Code /
Tamamo 的 `mcp.json`):

```json
{
  "mcpServers": {
    "jcgraph": {
      "command": "java",
      "args": ["-jar", "/abs/path/target/jcgraph.jar", "serve", "--mcp", "--db", "/abs/path/jcgraph.db"]
    }
  }
}
```

协议走 stdin/stdout;所有日志走 stderr。

## 已知的 MVP 限制

- 源码(`.java`)的调用边暂不生成(解析它们需要符号求解器);调用图来自字节码。源码贡献的是
  结构、位置、方法体。
- 调用图已做**多态解析**(OVERRIDES 边 + 查询时虚方法展开);污点传播(`taint`)已做到
  **方法内 + 沿调用链跨方法**,但**无堆/字段建模**(对象字段间传播靠库规则近似)。因此
  `taint` 的 FAIL 是"未证明"而非"安全";`trace`/`sinks` 保持高召回作兜底。下一步可加字段级
  数据流与框架(Spring/MyBatis)入口建模。
- 跨前端合并以 JVM 描述符为键。源码描述符是尽力而为(无类型解析),所以同时以 `.class` 和
  `.java` 出现的类能在类级别干净合并;方法级合并仅在描述符对得上时才精确。
- 字节码的全文 grep 能工作,是因为混合模式在索引时物化了反编译 `.java` 镜像;用
  `--no-materialize` 时,字节码只检索常量池 `strings` 表。
- 物化会在索引时反编译每个类(索引更慢、更占磁盘),但已**并行**执行且**支持中断续跑**
  (见上文)。方法位置通过重新解析反编译 `.java` 恢复;无法解析的输出回退到按需反编译 + 定位。
- 在 JDK 11+ 上,可把 `Decompiler` 换成 Vineflower,以额外获得 `bsm` 字节码↔源码行号映射。

## 许可证

污点分析引擎(`io.jcgraph.taint.*`)改编自 [jar-analyzer](https://github.com/jar-analyzer/jar-analyzer)
(作者 4ra1n / Jar Analyzer Team),原项目为 **GPLv3**。因此引入这部分代码后,本项目的衍生
分发须遵循 **GPLv3**。相关源文件头部保留了原始版权声明。规则数据 `propagation.json` /
`sanitizer.json` 同样来自该项目。
