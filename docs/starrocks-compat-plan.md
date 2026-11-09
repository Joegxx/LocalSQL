# StarRocks 方言支持设计（docs/starrocks-compat-plan.md）

> 目标：团队用 StarRocks 3.x 方言的 DQL + 管理语句，直接查询 LocalSQL 中已导入的 Hive 表（同一套 Catalog 元数据 + DuckDB 物理表），获得类似 StarRocks `SELECT * FROM hive_catalog.db.tbl` 的体验。方言在**连接时**经 JDBC URL session var 指定。
>
> 非目标：不新增 Hive metastore/HDFS 直连；不做 StarRocks 模型 DDL（`PRIMARY KEY`/`DISTRIBUTED BY` 等）；首版不支持 UDF 路径。

## 1. 方案选型

独立 StarRocks 前端（语法 + AST Builder）产出同一 IR。下游 analyzer → rewrite → generator → executor 零改动。

放弃的方案：
- 合并进 SqlBase.g4：破坏 verbatim 不变量；**决定性障碍：双引号语义相反**（Spark=标识符，StarRocks=字符串字面量），同一语法无法承载。
- 文本级转译：脆弱，嵌套子查询无法可靠区分。

## 2. 语法来源（已钉版本）

- **starrocks `3.5.21`**（2026-08-28，commit `85fccf8`，3.x 线最新），**不是 main**（main 已演进到 4.x 语法）。
- 3.5 结构下文件位于 `fe/fe-core/src/main/java/com/starrocks/sql/parser/`，本地拷贝到 `localsql-parser/src/main/antlr4/com/starrocks/sql/parser/`（目录即生成包名，保留原包名即保留出处证据）。
- 拷贝时文件 git hash（verbatim 凭证）：
  - `StarRocks.g4`: `7f815281b0dce42d0ed3f414fae6f90fd9990117`
  - `StarRocksLex.g4`: `e485e4021cba029c65f491c48d29c02695669cac`
- parser 文件（3115 行）零 FE 引用、零 action、零谓词，可 verbatim。
- lexer 文件**唯一补丁**（与上游 main 拆分 fe-grammar 模块时的内联做法完全一致）：
  - `@members` 中 `com.starrocks.qe.SqlModeHelper.MODE_DEFAULT` → `32L`
  - `LOGICAL_OR` 规则中 `SqlModeHelper.MODE_PIPES_AS_CONCAT` → `(1L << 1)`
  - 常量值从 branch-3.5 `com/starrocks/qe/SqlModeHelper.java` 逐一核实。
- 3.5.21 语法范围：DQL 核心全在（INTERSECT 优先级、`LIMIT a,b`、`QUALIFY`、`ROLLUP/CUBE/GROUPING SETS`、lambda、窗口 frame、`MINUS`）；**不含** `::`、ASOF、`* EXCLUDE`、`WITH RECURSIVE`（main 后来才加），拒绝清单相应缩短。
- ANTLR：FE 4.9.2 / 本项目 4.13.1，rule 参数语法两者兼容；`libDirectory` 需指向 StarRocks 语法目录以解析 `import StarRocksLex`（对 SqlBase.g4 无影响）。

## 3. sql_mode 决策

`StarRocksSqlParser` 固定 `sqlMode = MODE_DEFAULT(32) | PIPES_AS_CONCAT(1<<1) = 34`，即 `||` 词法化为 **CONCAT**。

依据：官方 TPC-DS 基准语料（`docs/zh/benchmarking/tpc_ds_99_sql.md`）中 12 处 `||` 全部按字符串拼接使用（如 `'store' || s_store_id`），且 DuckDB 的 `||` 即 concat，语义吻合。StarRocks 默认 sql_mode 下 `||` 是 LOGICAL_OR——该差异已记录，探针测试 `pipesAsConcatUnderSessionMode` / `pipesAsLogicalOrUnderDefaultMode` 同时锁定两种模式的行为。

## 4. 核心架构：DialectRuntime

```
JDBC (jdbc:hive2://host:10000/db;sql_dialect=starrocks)
        │
        ▼
OpenSession 读 configuration（sql_dialect / set:hiveconf: / set:var: 三种 key 形态）
        │  未知值 → LOG.warn + 回落 SPARK（绝不静默误配）
        ▼
SessionState.dialect (final，连接期不可切换)
        │
        ▼
SqlPipeline.execute(sql, currentDatabase, dialect)
        │  runtimes.get(dialect)  —— Map<Dialect, DialectRuntime>，构造期冻结
        ▼
Statement IR ──► Analyzer ──► Rewrite ──► Generator ──► DuckDB   （方言无感知）
```

- `DialectRuntime(SqlFrontend frontend, SemanticAnalyzer analyzer, RewriteEngine rewriter)`：三件套成对注册、一起变更，避免并行字段漂移（第三方言 = 加一个 bundle 条目）。
- `SqlFrontend` 为函数式接口（`Statement parse(String sql)`），实现用 lambda 组装，无 impl 类。
- **SPARK bundle** 包装现有组件，行为与改造前完全一致（561 项基线为零回退硬验收）。
- **STARROCKS bundle**（M1/M2 落地）：strict analyzer（`strictFunctions=true`）+ `StarRocksFunctionCatalog` + 方言作用域 RewriteEngine。未接线前 `execute` 抛 `UnsupportedOperationException` 大声失败。

## 5. 方言作用域函数降级（防 Spark 路径被静默放宽）

RewriteEngine 内拆两张表：
- **共享表**：两方言语义一致的条目（沿用现有 62 对改名 + lowering）。
- **StarRocks 专属表**：仅 starrocks 会话应用。

M2 门禁：每个新增函数名先做碰撞审计（查 `BuiltInFunctionCatalog`），冲突项定归属并加孪生回归。**负向测试：Spark 会话 `ifnull` 必须仍然失败**——沙箱承诺是"本地通过不误导生产"，Spark 方言下本地新放行的函数 = 假验证。

## 6. 方言差异归一化（StarRocksAstBuilder 前端归一）

| StarRocks 语法 | 归一化 | 复用能力 |
|---|---|---|
| `"abc"` 双引号 | → `StringLiteral` | 直接生成 |
| `LIMIT a,b` | → `Limit(offset,limit)` | 既有 Limit IR |
| `MINUS` | → EXCEPT | Union IR Kind |
| `\|\|`（CONCAT token） | → 拼接表达式 | Generator 原样发射 |
| `DATETIME`/`LARGEINT` cast | → `TIMESTAMP`/`HUGEINT` | 既有类型映射 |
| `CONVERT(x,t)` / `CAST(x AS t)` | → Cast IR | 既有 |
| `date_format`/`str_to_date` 的 `%` token | → Spark token | `SparkDialectFormats` lowering |
| `as "order count"` 字符串别名 | → 别名标识符 | 既有 alias |
| `BITSHIFTLEFT/BITSHIFTRIGHT[BLOGICAL]` | → 既有位移 IR | A1 第四批 |
| `hive_catalog.db.tbl` 三段名 | 原样进 IR | `resolveTableScan` 已取后两段 |
| `USE catalog.db` | 已支持 | `AdminStatementHandler.USE` 取最后一段 |

每条配"StarRocks 语法 vs Spark 语法"孪生回归。

显式拒绝（语法 parse 通过、AstBuilder 语义报清晰错误）：`QUALIFY`、`PIVOT`、queryPeriod（时态查询）、sampleClause/tabletList 等表后缀修饰、`MATCH` 全文检索（4.x 才有，3.5.21 不涉及）、StarRocks 模型 DDL 全族。

## 7. 模块布局

```
localsql-parser     + com/starrocks/sql/parser/{StarRocks,StarRocksLex}.g4
                    + StarRocksSqlParser 入口（复用 UpperCaseCharStream）
localsql-starrocks  新模块（M1）：StarRocksAstBuilder + StarRocksExpressionBuilder
                    + StarRocksTypeBuilder（package-private，镜像 localsql-spark）
                    依赖 {parser, ir}
localsql-thrift     Dialect / SqlFrontend / DialectRuntime / SqlPipeline 分发
                    AdminStatementHandler（M3）按方言优先分发模式集
app/pom             app -> {.., starrocks}；thrift -> {.., starrocks}
```

AdminStatementHandler 是 pre-grammar 权宜层（文本正则匹配 SHOW/USE/SET），方言边界在此强制：`tryHandle` 先按方言选模式集，两套模式不混用——Spark `SET x=y`（conf）与 StarRocks `SET CATALOG x`（catalog 切换）同形不同义。

## 8. 测试策略

- **L1 翻译回归**：官方 TPC-DS 99 条语料 verbatim 拆分至 `localsql-thrift/src/test/resources/tpcds-starrocks/`（q14/23/24 拆 a/b），经 StarRocks 前端 parse→analyze→rewrite→generate（挂 `TpcdsCatalogFixture`），通过率记录在案。
- **双方言孪生等价**（M4）：同语义 SQL 两方言翻译，`TpcdsMiniData` 上双向 `EXCEPT ALL` 为 0。
- **M0 探针**（已落地 `StarRocksLexerProbeTest` 9 项）：小写关键字、字面量/标识符大小写保持、`||` 双模式、双引号字符串、反引号、ROLLUP/MINUS/窗口冒烟、语法错误收集。
- **方言注入**（已落地 `DialectSessionTest` 6 项）：三种 key 形态、未知值回落、STARROCKS 大声失败、解析边界。
- 每步验收含 Spark 基线零回退：`mvn -o test '-Dtest=!TpcdsConsistencyTest,!TpcdsGoldenResultTest' -Dsurefire.failIfNoSpecifiedTests=false`。

## 9. 里程碑

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 分支 + 本文档 + 双 g4 verbatim + 补丁 + codegen + 探针 + DialectRuntime 骨架 + OpenSession 注入 | ✅ |
| M1 | `localsql-starrocks` 三类 builder DQL 全覆盖 + 归一化孪生回归 + STARROCKS bundle 接线 | |
| M2 | `StarRocksFunctionCatalog`（SPI）+ 方言作用域 rewrite + strict 契约 + 碰撞审计 | |
| M3 | Admin 方言优先分发 + `SHOW CATALOGS`/`SET CATALOG` + EXPLAIN 返回译文 + GetInfo 方言名 | |
| M4 | L3 双方言差分 + `docs/starrocks-compat.md` 三态矩阵 + usage/README/AGENTS.md | |

## 10. 已发现并顺手修复的存量问题

- commit `4491b3a` 把 admin 分支的 `OperationState` 参数改为 `colMetas()`，但本地工作区遗留了 pipeline 分支旧版 `columns()` 的未提交改动（与 HEAD 冲突且不编译）。M0 已恢复为 HEAD 的 `colMetas()` 并保留方言参数。

## 11. 环境注意事项

- `mvn` 不在默认 PATH：使用 `/opt/homebrew/bin/mvn`，且必须 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`。
- 本地 `.m2` 中上游模块快照可能过期：测试一律 `-pl localsql-thrift -am`（从源码构建上游），不要裸 `-pl localsql-thrift`。
