# AGENTS.md

面向 AI agent 的协作指南。2026-09-10 对照代码及本地审查更新。代码永远比文档更可信；“目标职责”不代表当前实现已满足。

当前修复范围与验收标准见 [docs/development-plan.md](docs/development-plan.md)。本次更新仅修改文档，计划中的代码修复均未实施。

## 设计哲学(最高优先级,违反即架构腐化)

1. **模块独立** - 模块边界即依赖边界,严禁跨层。
2. **IR 是唯一真相源** - 每个后端必须消费 IR,任何后端不得直接依赖 Spark ParseTree / ANTLR tree。
3. **Parser 只 parse** - 只产 parse-tree,不做任何逻辑。
4. **Analyzer 只 resolve** - 标识符解析、类型推导、函数解析、Catalog 查询。**Generator 默认 IR 已被 Analyzer 解析过。**
5. **Generator 只 serialize** - IR -> SQL 串,无状态,不查 Catalog、不解名、不重写。
6. **Executor 只 execute** - 跑 SQL,返回结果,不碰 IR。
7. **Catalog 只存元数据** - databases/schemas/tables/columns/function lookup。**禁止**执行 SQL、访问 JDBC、生成 DuckDB SQL、存 runtime 执行状态。
8. **Metadata 与 Runtime 分离** - Catalog 是逻辑元数据(Table/Column/View);DuckDB 是物理执行(CREATE TABLE/INSERT/SELECT)。两层不混。

## 这是什么

LocalSQL:解析 Spark SQL 3.2.0 并在 DuckDB 中执行的本地 SQL Sandbox，不是 Spark 执行引擎。当前流水线:

```
Spark SQL
   ↓
ANTLR parse
   ↓
Common IR (唯一真相源)
   ↓
Analyzer (已接入，名称与类型绑定未完成)
   ↓
Rewrite (已接入，转换逻辑仍有一部分位于 Generator)
   ↓
DuckDB SQL
   ↓
DuckDB execute
```

目标是无需部署 Spark/Hadoop/Hive 服务即可在本地跑数据仓库 SQL。已有 DQL、基础 DML/DDL、文件库导入/加载、进程内 UDF/UDTF 路径；仍有正确性及打包缺口，不能声称与 Spark 语义一致。

本地说明以 README、README.zh-CN、docs/usage.md 和开发计划为入口；。代码优先，不要求先访问外部设计文档。

## 构建与运行

- **必须 Java 21**(`maven.compiler.release=21`)。IntelliJ 的 `.idea/misc.xml` 可能显示 JDK 24 - 忽略,以 Maven 配置为准。
- 同时检查 `java -version` 与 `mvn -version`；两者可能使用不同 JDK，审查中 Maven 默认使用了 Java 24，需显式选择 Java 21。
- 多模块 Maven,根目录有父 pom。完整构建:`mvn clean package -DskipTests`
- 运行 demo(启动运行时,在 10000 端口监听 HiveServer2 Thrift,跑一个 JOIN/GROUP BY 样例查询):
  ```
  java -jar localsql-app/target/runtime.jar
  ```
  进程持续监听 Thrift 端口（Main 阻塞等待，shutdown hook 调用 stop）。可用参数覆盖默认端口 10000。
- `Main` 支持 `--import-ddl` / `--out` / `--load` / `--schema` / `--manifest` / `--failures` / `--udf-config` / `--function-map`。导入失败清理存在误删风险，只使用可丢弃副本；正式 JAR 的 UDF 配置入口目前缺少 Hive 运行依赖。

## 模块边界

主要内部依赖关系（省略重复直依赖与通用日志/测试依赖；实际以各模块 pom 为准）:

```
app -> {thrift, udf-runtime, parser, ir, spark, analyzer, rewrite, catalog, duckdb}
thrift -> {spark, parser, duckdb, catalog, analyzer, rewrite, udf-api}
spark -> {parser, ir}
analyzer -> {ir, catalog}
rewrite -> {ir, udf-api}
duckdb -> {ir, catalog}
catalog -> ir
udf-api -> ir
udf-runtime -> udf-api (+ Hive API provided, YAML)
parser -> (antlr runtime only)
ir -> (nothing)
```

- `localsql-parser` - ANTLR4 语法 + `SparkSqlParser` 入口。**只有 parse-tree,不做任何逻辑。**
- `localsql-ir` - 公共 IR。`Relation` 和 `Expression` 当前是 abstract class，不是 sealed 层级；`Statement` 表示顶层语句；`IrVisitor` 做开放递归。**严禁依赖 Spark 或 ANTLR。** IR 是唯一真相源，任何后端不得绕过 IR 直接读 parse-tree。
- `localsql-spark` - `SparkAstBuilder` 把 ParseTree 转成 IR。`SparkExpressionBuilder` 和 `SparkDataTypeBuilder` 是 package-private 辅助类。
- `localsql-catalog` - 目标是纯逻辑元数据 + 持久化抽象。当前为 `LinkedHashMap` 缓存，配置 `CatalogStore` 后已有持久化；`CatalogStore.RuntimeInfo` 与 `CatalogService.renameTable` 修改 SQL 是待移除的边界偏离，不能照此扩展。
- `localsql-duckdb` - Generator、JDBC Executor、`DuckDbCatalogStore` 和内置 Java 函数注册。当前 Generator 仍包含表达式重写；目标迁移至 Rewrite 后只序列化 IR。
- `localsql-udf-api` - UDF 定义、注册表、批数据与 Gateway 接口，不包含 Hive 执行实现。
- `localsql-udf-runtime` - 进程内 Hive UDF/GenericUDF/GenericUDTF 适配。独立 Gateway JVM 尚未实现，不能把 classloader 当作进程隔离或可靠超时机制。
- `localsql-thrift` - `SqlPipeline` 编排 SQL；`ThriftServer` 和 `LocalSqlThriftService` 提供 HiveServer2 RPC。已有 smoke test，但接口方法存在不等于类型、状态、取消与资源回收行为正确。
- `localsql-app` - `Main`、DDL 导入、函数配置加载，打包为 `runtime.jar`。

## ANTLR codegen - 关键坑

- 语法文件:`localsql-parser/src/main/antlr4/org/apache/spark/sql/catalyst/parser/SqlBase.g4`。**这是从 apache/spark `v3.2.0` tag 逐字复制的 Spark 3.2.0 SqlBase.g4。**
- **语法只改了一处**:`fragment LETTER` 从 `[A-Z]` 改成 `[a-zA-Z]`。原版只能匹配大写,因为 Spark 的 runtime lexer 在我们没有的 Java 代码里做大小写折叠。不改这个的话,小写标识符会被 tokenize 成 `UNRECOGNIZED`,任何真实查询都会在 `SELECT <小写>` 处失败。**不要"恢复"成上游版本。**
- **关键字大小写折叠靠 `UpperCaseCharStream`**:关键字规则(`SELECT: 'SELECT'` 等)仍是全大写,`SparkSqlParser.toCharStream` 用 `UpperCaseCharStream`(parser 模块内自写)包输入流:tokenize 时 `LA()` 返回大写、`getText()` 返回原文,从而关键字大小写不敏感、字符串字面量保持原样。这两个机制缺一不可,缺关键字折叠则 `select 1` 都会解析失败。
- 生成的 parser/lexer 类落在 `localsql-parser/target/generated-sources/antlr4/...` 下,package 是 `org.apache.spark.sql.catalyst.parser`。这个 package 故意和真 Spark 撞名(这样 g4 不改就能编);不要重命名。
- ANTLR 插件在 `compile` 时自动跑。只想重新生成:`mvn -pl localsql-parser antlr4:antlr4`。
- `SqlBase.g4` 里 parser 用了成员字段(`legacy_setops_precedence_enabled`、`SQL_standard_keyword_behavior` 等)默认 `false` - 保持原样,MVP 不动它们。

## 流水线执行顺序

执行查询时(见 `ThriftServer.executeSparkSql` 与 `LocalSqlThriftService.ExecuteStatement`,两者都经 `SqlPipeline.execute` 收敛):
1. `SparkSqlParser.parseStatement` -> `SingleStatementContext`
2. `SparkAstBuilder.buildStatement` -> IR `Statement`(`QueryStatement` / `Insert` / `CreateTable` / `CreateView` / `DropTable` / `CreateDatabase` / `Alter*` / `RenameTable` ...)
3. DQL(`QueryStatement`):`SemanticAnalyzer.analyze(rel)` -> `RewriteEngine.rewrite(rel)` -> `DuckDbSqlGenerator.generate` -> `DuckDbExecutor.execute`
4. DML/DDL 由 `SqlPipeline` 按类型编排(见下节)

补充：SHOW/DESCRIBE/USE 等管理语句由 `AdminStatementHandler` 先行处理；配置 Java UDF 后，查询可能走 UDF/UDTF 分阶段执行路径。修改缓存或会话行为时必须覆盖这些入口，不能只测试 `SqlPipeline.execute`。

### DML/DDL 编排(SqlPipeline)

- `Insert`:analyzer 校验目标表存在 + 列清单 ⊆ 表列;rewrite query;生成 `INSERT INTO tbl [(cols)] <query>`;`executeUpdate` 返回受影响行数,结果集为单列 `count`。
- `Insert.overwrite`:DuckDB 无 OVERWRITE,用 `BEGIN; DELETE FROM tbl; INSERT ...; COMMIT` 包裹(失败 ROLLBACK),替换整表数据。
- `CreateTable`(纯 DDL):`CREATE TABLE tbl (col TYPE [NOT NULL], ...)`;之后 `DESCRIBE tbl` 取物理 schema,经 `Catalog.parseDataType` 反推 IR 类型,`CatalogService.registerTable` 写元数据 + `saveRuntimeInfo` 写 `runtime_table`。
- `CreateTable` CTAS:`CREATE TABLE tbl AS <query>`(物理 schema 同样 DESCRIBE 反推);列定义 + AS query 组合 = 先建空表再走 Insert 路径。
- `CreateTable` LIKE:`tbl LIKE src`,从 Catalog 读源表 schema 生成 DDL。
- `CreateTable` PARTITIONED BY:纯 DDL 时分区字段带类型 -> AST builder 追加到列定义末尾(Spark 分区列在最后),裸引用 -> 校验已存在不改变 schema;`PARTITIONED BY + AS query`(CTAS)与 `CLUSTERED BY/SKEWED BY` 在 DuckDB 无对应概念,接受为无操作提示(直接按查询结果建表)。DuckDB 无分区概念,分区列就是普通列。
- `CreateView`:analyzer 校验 + rewrite query;生成 `CREATE [OR REPLACE] VIEW v AS <query>`;注册 tableType=`VIEW`。OR REPLACE 时先 `CatalogService.dropTable`(清 store 4 张表)+ `DROP VIEW IF EXISTS` 再建。
- `DropTable`:校验存在 + 类型匹配(DROP TABLE 不能删 VIEW,反之亦然);`IF EXISTS` 缺失时报错、存在则 no-op;`CatalogService.dropTable` 清 store + `DROP TABLE/VIEW IF EXISTS` 清 DuckDB 物理对象。
- `CreateDatabase`/`AlterDatabase`:当前仅更新内存，数据库级属性与空库尚未持久化。数据库是逻辑命名空间，无独立 DuckDB SCHEMA；`DropDatabase CASCADE` 编排物理表与元数据删除，无 `CASCADE` 且非空则报错；`default` 库不可删。
- `RenameTable`(`ALTER TABLE/VIEW RENAME TO`):校验跨库不支持;`DuckDbSqlGenerator` 生成 `ALTER TABLE/VIEW ... RENAME TO ...`;`CatalogService.renameTable` 更新 4 张表 + `runtime_table`。
- `AddColumns`/`DropColumns`/`RenameColumn`/`AlterColumn`/`ReplaceColumns`:校验列存在性;生成对应 DuckDB `ALTER TABLE ... ADD/DROP/RENAME/ALTER COLUMN` 语句,执行后 `DESCRIBE` + `refreshTable` 更新 Catalog;`REPLACE COLUMNS` 按新 schema 增删改列(保留重叠列数据),刷新后回填 comment/nullability。

**语义注意**:`INSERT INTO t VALUES (1,'a')` 的每一行在语法上是 `#rowConstructor`(`struct(...)`),AST builder 的 `visitInlineTable` 必须把它**展开成列**(`StructExpr.fields()` 当行内容),否则每行只算 1 列(DuckDB 报 "n columns but 1 values")。

**失败恢复缺口**：部分 DDL 通过 `inTransaction` 把物理操作与 store 写入包在同一 DuckDB 事务中，但内存 Catalog 不会回滚。失败 CTAS/替换视图后可能产生幽灵表或丢失缓存项；同步执行锁不能替代缓存恢复。

## SQL 生成约定(不要破坏)

`DuckDbSqlGenerator` 把 relation 拆成两类:
- **Query 节点**(`Project` / `Filter` / `Aggregate` / `Sort` / `Limit` / `Union` / `With` / `Generate`)- 发完整的 `SELECT ...`。当它们作为 `FROM` 子句的 source 时,**必须**通过 `emitChildSource` 加 `(...)` 包裹。
- **Source 节点**(`TableScan` / `Join` / `Values` / `SubqueryAlias`)- 直接发表表达式,不加 `SELECT *` 前缀。`Join` 直接发 `left JOIN right ON ...`;`SubqueryAlias` 发 `(query) AS alias`。

`emitJoin` **不**加 `SELECT * FROM` 前缀 - 之前是 bug。`FROM` 子句必须走 `emitChildSource`,永远不要裸用 `emit`。

`DuckDbSqlGenerator` 的**目标**是无执行状态的序列化器。当前持有不可变函数配置，但还承担函数降级与表达式重写，须按开发计划迁移。**禁止新增**:
- 查 Catalog
- 解析名称(标识符解析是 Analyzer 的活)
- 重写表达式

目标职责是 IR -> SQL 序列化。当前 Analyzer 尚未保证输入全部已解析；需要逐步建立已解析 IR 校验闸门，不能在 Generator 中用裸表名或默认类型掩盖未解析节点。

## Analyzer 职责(名字解析的唯一归属)

Analyzer 的目标职责:
- **标识符解析** - `SELECT name FROM user` 里 `name` 到底是 `user.name` 还是 `dept.name`,由 Analyzer 解析,Generator 不查 Catalog。
- **类型推导** - 给 `AttributeReference` 设 DataType(用 `IrNode.setDataType`)。
- **函数解析** - 函数签名/重载。
- **Catalog 查询** - 把 `TableScan.output` 填上真实列。

Generator 永远不用查 Catalog。

当前仅填充能查到的 TableScan.output 并做部分语句检查；找不到表不会报错，普通 Identifier 尚未绑定，AttributeReference 的未知类型会被默认设为 STRING。不能把这些行为当作完整解析的实现或继续依赖它们。

## 函数映射归属(避免双重映射)

现状：Rewrite 已接入且会 rename；Generator 的 `FN_MAP`、`emitMappedFunction`、自定义 rename/template 仍在做转换。二者并非独占关系，存在重复映射和覆盖顺序风险。

M2 已落地 A3 元数据契约：analyzer 模块新增 `FunctionCatalog`(只读 SPI)、`FunctionInfo`(name/aliases/arity/kind)、`FunctionKind`(SCALAR/AGGREGATE/WINDOW/TABLE)、`BuiltInFunctionCatalog`(Spark 3.2 内置名单,别名 substr→substring、ceiling→ceil、power→pow、char_length→length)。`SemanticAnalyzer(catalog, functions, strictFunctions)` 在解析 `FunctionCall` 时查目录；strict 模式拒绝未知函数，lenient 模式(SqlPipeline 当前默认)透传。别名重定向是元数据层行为,不影响 Rewrite/Generator 的翻译归属。

目标：Analyzer 绑定函数身份、签名、类型；Rewrite 独占函数降级与表达式转换；Generator 仅输出已降级 IR；Executor/Gateway 只注册或执行运行时函数。

迁移按函数族进行，先补回归、把规则迁入 Rewrite、切换调用方，再删除对应 Generator 分支。最终移除 `FN_MAP` 与 Generator 的原始模板替换入口；过渡期每个函数只能有一个生效的转换归属。具体规则与配置兼容方案见开发计划 A1/A3。

## 已知 MVP 缺口

- DML/DDL 支持:`INSERT INTO/OVERWRITE`、`CREATE TABLE`(含 CTAS / LIKE / PARTITIONED BY)、`CREATE VIEW`、`DROP TABLE/VIEW`、`CREATE/DROP/ALTER DATABASE`、`ALTER TABLE` (ADD/DROP/RENAME COLUMN, ALTER COLUMN TYPE/NOT NULL, REPLACE COLUMNS, RENAME TO, SET/UNSET TBLPROPERTIES) 已实现;`DELETE/UPDATE/MERGE`、multi-INSERT 未实现
- `Aggregate.aggregateExpressions` 被重载成整个 select list(分组列 + 聚合在一起)- 不是干净的 Spark 风格拆分
- 命名窗口(`WINDOW w AS ...`)、聚合 `FILTER` 子句、`NATURAL JOIN`(需要同名列解析)抛 `UnsupportedOperationException`
- 位运算/整数除法/`<=>`/`JOIN USING` 已正确映射(`INT_DIV` -> `//`、`BIT_XOR` -> `xor()`、`EQ_NULL_SAFE` -> `IS NOT DISTINCT FROM`)
- Catalog 已有内存缓存 + DuckDB store；缺口是失败一致性、runtime 职责分离和数据库级持久化，不是尚未接入 store
- Analyzer 严格名称解析已落地（M2/F1）：分层 Scope、错库/错表/缺列/歧义执行前拒绝、Star 展开、ORDER BY/HAVING 别名可见性（select 输出优先）、lambda 变量绑定、alias 屏蔽表名；类型传播仍不完整。DDL 缓存回滚（F2）与 UDF 兼容（F3）已修复；语法静默丢失（F4）、Thrift（F5）已修复；UDF 打包依赖缺口仍在
- 导入异常清理会按裸表名删除物理表，可能误删其他库已有对象；该独立风险未纳入本轮六项主线，修复前禁止在不可丢弃的数据副本上验证导入

## TPC-DS 统一测试

`TpcdsQueryPipelineTest` 参数化跑每个查询的完整流水线(parse -> analyze -> rewrite -> generate),不执行。两组资源:
- `localsql-thrift/src/test/resources/tpcds-v2.7.0/` - 32 个查询,apache/spark `a2da2926` 的 `sql/core/src/test/resources/tpcds-v2.7.0`
- `localsql-thrift/src/test/resources/tpcds/` - 103 个查询,apache/spark `master` 的 `sql/core/src/test/resources/tpcds`(完整 q1..q99 + a/b 变体)

测试资源共 135 份 SQL；`TpcdsQueryPipelineTest` 另有 1 个汇总测试，因此该类为 136 项测试，不是 136 条查询。`UNSUPPORTED` 当前为空。窗口函数（含 frame）与 ROLLUP/CUBE/GROUPING SETS 已有实现；命名窗口和聚合 FILTER 仍显式拒绝。L1 已接入 `TpcdsCatalogFixture`（24 表 v2.7.0 全 schema 元数据，内嵌在测试代码；customer 表额外补 v2.7 的 `c_last_review_date`），严格 Analyzer 在此 fixture 上绑定全部 135 个查询，不能回退到空 Catalog。

### 分层测试架构

- **L1 翻译回归** - `TpcdsQueryPipelineTest`:parse -> analyze -> rewrite -> generate,不执行
- **L3 结果一致性(手工 golden)** - `TpcdsGoldenResultTest`:走真实 Thrift 端点执行,结果与 Spark 语义的手工校准期望对比
- **L3 结果一致性（DuckDB 差分）** - `TpcdsConsistencyTest`：同一份数据（`dsdgen(sf=0.01)` 生成 24 表）、同一条 SQL，期望侧 DuckDB 原生执行，实际侧 Thrift 管线。字符串化后用双向 `EXCEPT ALL` 比较多重集；不验证结果类型、行顺序，也不是 Spark oracle。原文不能执行的查询只验证 Thrift 成功，不能计为语义一致通过。

L3 组件(全部 DuckDB 自举):
- `ThriftQueryRunner` - Thrift 客户端封装,启动 in-process server 并执行查询取回行
- `TpcdsMiniData` - 确定性 mini 数据集(手工 golden 用),带干扰行;同时注册 Catalog 元数据 + DuckDB 物理表
- `TpcdsConsistencyTest` - 差分测试本体;`ORACLE_UNAVAILABLE` 列出原文在 DuckDB 无法执行的查询(保留字别名 `returns`/`at`),只断言 thrift 侧成功;dsdgen 的 customer 表是 1.x schema,会 `ALTER TABLE ADD COLUMN c_last_review_date DATE` 补 2.7 的列
- 对比 - 实际/期望都灌成 DuckDB VARCHAR 表,双向 `EXCEPT ALL` 计数为 0 即通过

差分测试暴露并修复的翻译 bug(全部有回归保护):
- `INTERSECT`/`EXCEPT` 被当 `UNION DISTINCT`(Union IR 加 `Kind`,之前是 critical 语义错误)
- 负数字面量丢符号(Spark grammar 的 `number: MINUS? INTEGER_VALUE` 把负号折进 number,`visitNumber` 现在读 `MINUS()`)
- `SELECT DISTINCT` 被静默丢弃(Project 加 `distinct`)
- `DECIMAL(p,s)` cast 变 VARCHAR(新增 `DecimalType` IR 类型)
- 反引号标识符保留 `` ` `` 当名字一部分(统一 `unquote`)
- DuckDB 保留字(`null`/`returns`/`at`/`order` 等)不引号化(`quote()` 加保留字集合)
- `SubqueryAlias` 别名为 Java null 时生成 `AS null`
- FROM 聚合子查询时外层投影被丢(`visitQuerySpecification` 的 `instanceof Aggregate` 短路误伤,改显式 `aggregated` 标志)
- `LIKE`/`RLIKE` 生成不存在的 `like()` 函数(改 `x LIKE y` / `x REGEXP y` 语法)

`DuckDbSqlGenerator` 的 `emitProject`/`emitAggregate` 会把直接子 `Filter` **内联为自身 WHERE**(不包子查询),否则子查询作用域会丢掉表别名限定(`FROM date_dim AS dt` 在派生表里会让外层 `dt.d_year` 失效)。

## MVP 不做什么(不要主动加)

- optimizer / cost model / statistics
- materialized view
- 用户级 transaction / ACID / snapshot（不排除修复现有内部事务的原子性）
- distributed execution
- 权限/认证

## 当前持久化布局与下一阶段

现有 4 张内部表（已实现，不是待接入设计）：

- `catalog_table`：table_id、catalog/database/schema/table 名、类型、注释、properties 兼容列、metadata_json。
- `catalog_column`：table_id、ordinal、列名、类型、nullable、注释、default_value、expression。
- `catalog_property`：table_id、property_key、property_value；表属性实际从这里加载。
- `runtime_table`：table_id、duck_table_name、create_sql、last_refresh；这是物理运行信息，目标从 CatalogStore 接口分离。

`metadata_json` 是可选原始元数据快照，不保证所有入口都会填充。`--load` 直接打开已有 DuckDB 文件并从 store 加载 Catalog；当前没有启动时统一重放 `create_sql` 的流程，不能宣称 DDL 重放能恢复数据或全部依赖对象。

已完成的基础接入：

- `DuckDbCatalogStore` 已实现；`CatalogService(store)` 启动时加载表元数据。
- `TableScan.setOutput` 与 Analyzer 填充输出已存在，不需要再次实现接入。
- `SqlPipeline` 已统一调用 Analyzer/Rewrite，Thrift 与直接执行入口复用它。
- Main 已支持 typed 样例、DDL 文件导入、持久化库加载、UDF 与函数映射配置。

下一阶段按 [开发计划](docs/development-plan.md) 执行：A1 Generator → Rewrite、A2 CatalogService 边界、A3 函数转换统一，以及 F1–F6 六项正确性修复。A1 前两批已落地：`FunctionNameRewriter`（rewrite 模块）持有 62 个 1:1 改名对（Spark 源名→DuckDB 名，如 instr→strpos、size→array_length、mean→avg）；`ExpressionLowering`（rewrite 模块）把 40 个参数重排/包装函数降级为纯 IR（if→CASE WHEN、isnull→IS NULL、datediff→date_diff 重排、to_date→CAST/strptime、count_if→count(CASE)、collect_set→list(DISTINCT)、current_catalog→字面量等），关系树经 RewriteEngine 重建返回新树，`SqlPipeline` 已改为使用返回值（A1 验收：调用方必须用返回值）。Generator 的 `FN_MAP` 已删除这 62+1 对、`emitMappedFunction` 已删除对应 34 个 case；`map_from_arrays` 因 DuckDB `case "map"` 参数重排冲突留待 map 族一起迁移。A1 第三批已迁：CAST 类型函数（string/tinyint/.../decimal(x,p,s)→Cast IR）、sort_array、sequence、date_trunc（unit 字面量翻译）、sha2、reflect/java_method（REFLECT_MAP 移入 Rewrite）、array_repeat。仍留 Generator 的特判族：lambda 高阶函数（transform/zip_with/map_zip_with/map_filter/exists/forall/aggregate/array_except/array_join/array_remove）、字面量模板族（date_format/from_json/named_struct/next_day/substring_index/format_number）、位运算/溢出（shift/getbit/bit_*/try_add，需 IR 加 SHIFT/try_cast 支持）、时区（from/to_utc_timestamp，AT TIME ZONE 语法）、bround/conv/parse_url（连接级 Java UDF）。新增数据库元数据表与存储版本迁移是拟议方案，尚未实现；正式迁移前必须确认旧库兼容与备份策略。

## 验证基线（2026-09-11）

- Java 21 下 `mvn -o package -DskipTests` 成功。
- 排除 `TpcdsConsistencyTest`/`TpcdsGoldenResultTest`（需真实端口与 tpcds 扩展）后 `mvn -o test '-Dtest=!TpcdsConsistencyTest,!TpcdsGoldenResultTest' -Dsurefire.failIfNoSpecifiedTests=false` 全量 554 项通过。
- 全量命令在本沙箱可完成 bind；TPC-DS 翻译 136 项（135 SQL + 汇总）含 24 表 fixture 绑定全绿，真实端口差分仍未跑。
- M2 验收覆盖：F1AnalyzerBindingTest 20 项（USE/限定名/LIKE/CTE/子查询/self join/别名/JOIN USING 正反例 + 输出 schema）、FunctionCatalogContractTest 11 项（A3 契约）、SemanticAnalyzerContractTest 4 项（strict 闸门）。

## 约定

- 没有明确要求时不加代码函数注释，仅添加类注释
- IR 节点类：一个文件一个 public 顶层类；`DataType.java` 只放类型接口，具体类型单独放在 `ir/type/`
- `FunctionCall.name` 是可变的(通过 `rename()`),专门为 rewrite 层原地改名字设计
- `Main` 样例数据使用真实数值/字符串类型；不要退回全部 VARCHAR 掩盖类型问题
