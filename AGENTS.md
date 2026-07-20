# AGENTS.md

面向 AI agent 的协作指南。内容已对照当前代码核对,代码永远比文档更可信。

## 这是什么

LocalSQL:内嵌的 Spark SQL 运行时。流水线:`Spark SQL → ANTLR parse → Common IR → rewrite → DuckDB SQL → DuckDB execute`。目标是无需部署 Spark/Hadoop/Hive 即可在本地跑数据仓库 SQL。MVP 仅支持 DQL。

设计文档在飞书 wiki(标题"SQL Runtime");仓库 README 只有一行。代码优先。

## 构建与运行

- **必须 Java 21**(`maven.compiler.release=21`)。IntelliJ 的 `.idea/misc.xml` 可能显示 JDK 24 — 忽略,以 Maven 配置为准。
- 多模块 Maven,根目录有父 pom。完整构建:`mvn clean package -DskipTests`
- 运行 demo(启动运行时,在 10000 端口监听 HiveServer2 Thrift,跑一个 JOIN/GROUP BY 样例查询):
  ```
  java -jar localsql-app/target/runtime.jar
  ```
  进程持续监听 Thrift 端口(Main 在 shutdown hook 上 join)。可用参数覆盖默认端口 10000。

## 模块边界

依赖关系(左依赖右):

```
app → thrift → {spark, parser, duckdb, catalog}
spark → {parser, ir}
analyzer → {ir, catalog}
rewrite → ir
duckdb → {ir, catalog}
catalog → ir
parser → (antlr runtime only)
ir → (nothing)
```

- `localsql-parser` — ANTLR4 语法 + `SparkSqlParser` 入口。**只有 parse-tree,不做任何逻辑。**
- `localsql-ir` — 公共 IR。`Relation`(relation/)和 `Expression`(expression/)是类 sealed 层级;`IrVisitor` 做开放递归。**严禁依赖 Spark 或 ANTLR。**
- `localsql-spark` — `SparkAstBuilder` 把 ParseTree 转成 IR。`SparkExpressionBuilder` 和 `SparkDataTypeBuilder` 是 package-private 辅助类。
- `localsql-catalog` — 元数据模型(`Catalog` / `Column` / `Table` / `Database`)+ 持久化抽象(`CatalogStore`)。**不依赖 DuckDB。**
- `localsql-duckdb` — `DuckDbSqlGenerator`(IR → SQL 串)+ `DuckDbExecutor`(JDBC 操作进程内 DuckDB)+ `DuckDbCatalogStore`(实现 `CatalogStore`,把元数据落到 4 张 DuckDB 表)。
- `localsql-thrift` — `ThriftServer` 编排完整流水线,真实提供 HiveServer2 `TCLIService` Thrift RPC(端口 10000,`TThreadPoolServer`)。`LocalSqlThriftService` 实现了全部 23 个 `TCLIService.Iface` 方法;`src/test` 里有 smoke test 覆盖 OpenSession→ExecuteStatement→FetchResults 和 GetTables/GetColumns。
- `localsql-app` — `Main` 装载样例数据,打包为 `runtime.jar`。

## ANTLR codegen — 关键坑

- 语法文件:`localsql-parser/src/main/antlr4/org/apache/spark/sql/catalyst/parser/SqlBase.g4`。**这是从 apache/spark `v3.2.0` tag 逐字复制的 Spark 3.2.0 SqlBase.g4。**
- **语法只改了一处**:`fragment LETTER` 从 `[A-Z]` 改成 `[a-zA-Z]`。原版只能匹配大写,因为 Spark 的 runtime lexer 在我们没有的 Java 代码里做大小写折叠。不改这个的话,小写标识符会被 tokenize 成 `UNRECOGNIZED`,任何真实查询都会在 `SELECT <小写>` 处失败。**不要"恢复"成上游版本。**
- 生成的 parser/lexer 类落在 `localsql-parser/target/generated-sources/antlr4/...` 下,package 是 `org.apache.spark.sql.catalyst.parser`。这个 package 故意和真 Spark 撞名(这样 g4 不改就能编);不要重命名。
- ANTLR 插件在 `compile` 时自动跑。只想重新生成:`mvn -pl localsql-parser antlr4:antlr4`。
- `SqlBase.g4` 里 parser 用了成员字段(`legacy_setops_precedence_enabled`、`SQL_standard_keyword_behavior` 等)默认 `false` — 保持原样,MVP 不动它们。

## 流水线执行顺序

执行查询时(见 `ThriftServer.executeSparkSql`):
1. `SparkSqlParser.parseStatement` → `SingleStatementContext`
2. `SparkAstBuilder.buildStatement` → IR `Relation`
3. (Analyzer / RewriteEngine 存在但**当前未接入**,`ThriftServer` 不调用它们 — 见"在途工作"章节)
4. `DuckDbSqlGenerator.generate` → DuckDB SQL 串
5. `DuckDbExecutor.execute` → `QueryResult`

## SQL 生成约定(不要破坏)

`DuckDbSqlGenerator` 把 relation 拆成两类:
- **Query 节点**(`Project` / `Filter` / `Aggregate` / `Sort` / `Limit` / `Union` / `With` / `Generate`)— 发完整的 `SELECT ...`。当它们作为 `FROM` 子句的 source 时,**必须**通过 `emitChildSource` 加 `(...)` 包裹。
- **Source 节点**(`TableScan` / `Join` / `Values`)— 直接发表表达式,不加 `SELECT *` 前缀。`Join` 直接发 `left JOIN right ON ...`。

`emitJoin` **不**加 `SELECT * FROM` 前缀 — 之前是 bug。`FROM` 子句必须走 `emitChildSource`,永远不要裸用 `emit`。

Spark 函数 → DuckDB 的映射放在 `FN_MAP`(例如 `size` → `array_length`,`explode` → `unnest`)。`RewriteEngine` 也有 rename 逻辑但当前是死代码 — `FN_MAP` 是实际生效的路径。

`DuckDbSqlGenerator` 是无状态的。**不能**:
- 查 Catalog
- 解析名称
- 重写表达式

它的唯一职责是 IR → SQL 序列化。

## Analyzer 职责

按 AGENTS.md 注释的设计原则(在途的接入工作就是落实这条):
- 标识符解析
- 类型推导
- 函数解析
- Catalog 查询

Generator 默认 IR 已经被 Analyzer 解析过,自身只做序列化。

## 已知 MVP 缺口

- DDL 未实现(CREATE / ALTER / DROP 是 Phase 2)
- `RewriteEngine` 和 `SemanticAnalyzer` **当前未接入运行路径**。`thrift` 模块的 pom **没有**声明 `analyzer` / `rewrite` 依赖(只有 `app` 声明了)— 接入需要在 `localsql-thrift/pom.xml` 加依赖并在 `ThriftServer.executeSparkSql` / `LocalSqlThriftService.ExecuteStatement` 之间调 `analyzer.analyze` + `rewriter.rewrite`
- 表别名只对 `TableScan` 生效;子查询别名(`AliasedQuery`)被丢弃(见 `SparkAstBuilder.alias` — 只处理 `TableScan`)
- `Aggregate.aggregateExpressions` 被重载成整个 select list(分组列 + 聚合在一起)— 不是干净的 Spark 风格拆分
- ROLLUP / CUBE / GROUPING SETS 抛 `UnsupportedOperationException`(在 `SparkAstBuilder.visitAggregate`)

## 在途工作:DuckDB 元数据 + Analyzer / RewriteEngine 接入

**目标**:把测试数据存到 DuckDB,让 DuckDB 模拟出 Hive 元数据(4 张表),让外部通过 Thrift 能查到表 DDL;同时把 Analyzer 和 RewriteEngine 真正接入运行链路。

### 元数据设计(用户已确认的 4 张 DuckDB 表)

```
catalog_table      (table_id, catalog_name, database_name, schema_name,
                    table_name, table_type, comment, properties, metadata_json)
catalog_column     (table_id, ordinal, column_name, data_type, nullable,
                    comment, default_value, expression)
catalog_property   (table_id, property_key, property_value)
runtime_table      (table_id, duck_table_name, create_sql, last_refresh)
```

`metadata_json` 用来存完整原始元数据快照,后续要加新字段不用改表结构。`runtime_table` 存 DuckDB 实际注册的 DDL,重启时 `SELECT create_sql FROM runtime_table` 重放即可恢复。

### 设计决策

1. **`Catalog` 模块保持纯(不依赖 DuckDB)** — 用 `CatalogStore` 接口做持久化抽象,在 `localsql-duckdb` 模块里实现 `DuckDbCatalogStore`。
2. **`Catalog` 当前是内存缓存**(`LinkedHashMap`),`CatalogService` 持有 `CatalogStore` 时,启动从 store load,写入同时落 store + cache。
3. **TableScan.output 必须可变** — 当前 `TableScan` 的 `output` 字段是 `final` + `List.copyOf()`,Analyzer 没法把查到的列塞回去。改成可变 + 加 `setOutput` 方法。
4. **Analyzer 修复** — `visitTableScan` 当前查 Catalog 后**丢弃**了结果(`out` list 没赋回),要改成真的 `r.setOutput(out)`。同时给 `AttributeReference` 设 DataType(用 `IrNode.setDataType`)。
5. **RewriteEngine 接入** — 它已经会 `rename` 函数名(`size` → `array_length` 等),接进去就行;`FN_MAP` 后续可考虑删掉(避免双重映射)。
6. **`thrift` pom 需加** `localsql-analyzer` 和 `localsql-rewrite` 两个依赖。
7. **ThriftServer / LocalSqlThriftService 的 ExecuteStatement 都要接入** — 两处当前都直接 parse → generate,要插入 analyze + rewrite。
8. **Main 重写** — 现在 `Main` 同时手动调 `catalogService.registerSampleTable`(Catalog)+ `executor.registerSampleTable`(DuckDB 数据),数据重复。改成:`DuckDbCatalogStore` 初始化 → 注册表时同时写元数据表 + 建 DuckDB 数据表 + 写 `runtime_table`。
9. **SmokeTest 同步更新** — `ThriftServerSmokeTest` 现在直接用 `CatalogService` + `DuckDbExecutor`,改成用 `DuckDbCatalogStore`。

### 进度

| 状态 | 文件 | 说明 |
|------|------|------|
| ✅ | `localsql-catalog/.../Catalog.java` | `Table` / `Column` record 富化:加 `tableId`、`tableType`、`properties`、`metadataJson`、`ordinal`、`defaultValue`、`expression`;保留旧构造器做向后兼容;加 `parseDataType` / `toStorageType` 工具方法 |
| ✅ | `localsql-catalog/.../CatalogStore.java` | 新建。`init()` / `saveTable()` / `loadTable()` / `loadTables()` / `saveRuntimeInfo()` / `loadAllRuntimeInfo()` |
| ✅ | `localsql-catalog/.../CatalogService.java` | 加 `(CatalogStore)` 构造器,启动时 `loadFromStore`;`registerTable` 落 store + cache;保留 `registerSampleTable` 向后兼容 |
| ❌ | `localsql-duckdb/.../DuckDbCatalogStore.java` | **待建**。实现 `CatalogStore`,建 4 张表(`CREATE TABLE IF NOT EXISTS`),用 `executor.executeQuery` / `executeUpdate` 操作 |
| ❌ | `localsql-ir/.../TableScan.java` | **待改**。`output` 字段去掉 `final`,加 `setOutput(List<AttributeReference>)` 方法 |
| ❌ | `localsql-analyzer/.../SemanticAnalyzer.java` | **待改**。`visitTableScan` 真正 `r.setOutput(out)`;按 column.type() 给 AttributeReference `setDataType`;递归处理子节点上的 AttributeReference |
| ❌ | `localsql-thrift/pom.xml` | **待改**。加 `localsql-analyzer` 和 `localsql-rewrite` 两个 dependency |
| ❌ | `localsql-thrift/.../ThriftServer.java` | **待改**。`executeSparkSql` 在 `buildStatement` 之后、`generator.generate` 之前加 `analyzer.analyze(rel)` + `rewriter.rewrite(rel)`;构造器接受 `SemanticAnalyzer` + `RewriteEngine`(或内部 new) |
| ❌ | `localsql-thrift/.../LocalSqlThriftService.java` | **待改**。`ExecuteStatement` 同样接入 analyzer + rewrite |
| ❌ | `localsql-app/.../Main.java` | **待改**。用 `DuckDbCatalogStore` + `DuckDbExecutor`,`registerTable` 一次完成元数据 + 数据 + runtime_table 写入;data 里的 `age` / `amount` / `id` 改成正确类型(不再全 VARCHAR) |
| ❌ | `localsql-thrift/.../ThriftServerSmokeTest.java` | **待改**。用 `DuckDbCatalogStore` |
| ❌ | 跑 `mvn test` 全部通过 | **待验证**。当前已完成 3/12 步,partial 状态下编译和测试都过 ✅ |

### 下次从这里继续

按上表倒序:先建 `DuckDbCatalogStore` → 改 `TableScan` → 修 `SemanticAnalyzer` → thrift pom 加依赖 → 接 ThriftServer / LocalSqlThriftService → 重写 Main → 更新测试 → 跑全套 `mvn test`。

`CatalogStore` 接口已定,`DuckDbCatalogStore` 实现时直接 `implements CatalogStore` 即可。`init()` 里发 `CREATE TABLE IF NOT EXISTS catalog_table ...` 等 4 条 DDL。`loadTable(database, tableName)` 用 `SELECT * FROM catalog_table JOIN catalog_column USING (table_id) WHERE database_name = ? AND table_name = ? ORDER BY ordinal` 然后手动组 `Catalog.Table`。

`DuckDbExecutor` 已有 `executeQuery(String) -> QueryResult` 和 `executeUpdate(String) -> int`,直接复用。

## 约定

- 没有明确要求时不加代码注释
- IR 节点类:一个文件一个 public 顶层类(Java 对 record / sealed member 的硬性要求 — `DataType.java` 只放 sealed interface,具体类型各自单独文件放在 `ir/type/`)
- `FunctionCall.name` 是可变的(通过 `rename()`),专门为 rewrite 层原地改名字设计
- `Main` 样例数据用 VARCHAR — DuckDB 会做强转
