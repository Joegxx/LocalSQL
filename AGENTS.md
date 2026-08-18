# AGENTS.md

面向 AI agent 的协作指南。内容已对照当前代码核对,代码永远比文档更可信。

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

LocalSQL:内嵌的 Spark SQL 运行时。流水线:

```
Spark SQL
   ↓
ANTLR parse
   ↓
Common IR (唯一真相源)
   ↓
Analyzer (当前 no-op,未接入)
   ↓
Rewrite (当前 no-op,未接入)
   ↓
DuckDB SQL
   ↓
DuckDB execute
```

目标是无需部署 Spark/Hadoop/Hive 即可在本地跑数据仓库 SQL。MVP 仅支持 DQL。

设计文档在飞书 wiki(标题"SQL Runtime");仓库 README 只有一行。代码优先。

## 构建与运行

- **必须 Java 21**(`maven.compiler.release=21`)。IntelliJ 的 `.idea/misc.xml` 可能显示 JDK 24 - 忽略,以 Maven 配置为准。
- 多模块 Maven,根目录有父 pom。完整构建:`mvn clean package -DskipTests`
- 运行 demo(启动运行时,在 10000 端口监听 HiveServer2 Thrift,跑一个 JOIN/GROUP BY 样例查询):
  ```
  java -jar localsql-app/target/runtime.jar
  ```
  进程持续监听 Thrift 端口(Main 在 shutdown hook 上 join)。可用参数覆盖默认端口 10000。

## 模块边界

依赖关系(左依赖右):

```
app -> thrift -> {spark, parser, duckdb, catalog}
spark -> {parser, ir}
analyzer -> {ir, catalog}
rewrite -> ir
duckdb -> {ir, catalog}
catalog -> ir
parser -> (antlr runtime only)
ir -> (nothing)
```

- `localsql-parser` - ANTLR4 语法 + `SparkSqlParser` 入口。**只有 parse-tree,不做任何逻辑。**
- `localsql-ir` - 公共 IR。`Relation`(relation/)和 `Expression`(expression/)是类 sealed 层级;`IrVisitor` 做开放递归。**严禁依赖 Spark 或 ANTLR。** IR 是唯一真相源,任何后端不得绕过 IR 直接读 parse-tree。
- `localsql-spark` - `SparkAstBuilder` 把 ParseTree 转成 IR。`SparkExpressionBuilder` 和 `SparkDataTypeBuilder` 是 package-private 辅助类。
- `localsql-catalog` - **只存逻辑元数据**(`Catalog` / `Column` / `Table` / `Database`)+ 持久化抽象(`CatalogStore`)。**禁止**:执行 SQL、访问 JDBC、生成 DuckDB SQL、存 runtime 执行状态。当前 MVP 是内存 `LinkedHashMap`,后续会换成 DuckDB-backed store,**不改调用方**。
- `localsql-duckdb` - `DuckDbSqlGenerator`(IR -> SQL 串,无状态序列化器)+ `DuckDbExecutor`(JDBC 操作进程内 DuckDB)+ `DuckDbCatalogStore`(实现 `CatalogStore`,把元数据落到 4 张 DuckDB 表)。DuckDB 负责**物理执行**,不是逻辑元数据。
- `localsql-thrift` - `ThriftServer` 编排完整流水线,真实提供 HiveServer2 `TCLIService` Thrift RPC(端口 10000,`TThreadPoolServer`)。`LocalSqlThriftService` 实现了全部 23 个 `TCLIService.Iface` 方法;`src/test` 里有 smoke test 覆盖 OpenSession->ExecuteStatement->FetchResults 和 GetTables/GetColumns。
- `localsql-app` - `Main` 装载样例数据,打包为 `runtime.jar`。

## ANTLR codegen - 关键坑

- 语法文件:`localsql-parser/src/main/antlr4/org/apache/spark/sql/catalyst/parser/SqlBase.g4`。**这是从 apache/spark `v3.2.0` tag 逐字复制的 Spark 3.2.0 SqlBase.g4。**
- **语法只改了一处**:`fragment LETTER` 从 `[A-Z]` 改成 `[a-zA-Z]`。原版只能匹配大写,因为 Spark 的 runtime lexer 在我们没有的 Java 代码里做大小写折叠。不改这个的话,小写标识符会被 tokenize 成 `UNRECOGNIZED`,任何真实查询都会在 `SELECT <小写>` 处失败。**不要"恢复"成上游版本。**
- **关键字大小写折叠靠 `UpperCaseCharStream`**:关键字规则(`SELECT: 'SELECT'` 等)仍是全大写,`SparkSqlParser.toCharStream` 用 `UpperCaseCharStream`(parser 模块内自写)包输入流:tokenize 时 `LA()` 返回大写、`getText()` 返回原文,从而关键字大小写不敏感、字符串字面量保持原样。这两个机制缺一不可,缺关键字折叠则 `select 1` 都会解析失败。
- 生成的 parser/lexer 类落在 `localsql-parser/target/generated-sources/antlr4/...` 下,package 是 `org.apache.spark.sql.catalyst.parser`。这个 package 故意和真 Spark 撞名(这样 g4 不改就能编);不要重命名。
- ANTLR 插件在 `compile` 时自动跑。只想重新生成:`mvn -pl localsql-parser antlr4:antlr4`。
- `SqlBase.g4` 里 parser 用了成员字段(`legacy_setops_precedence_enabled`、`SQL_standard_keyword_behavior` 等)默认 `false` - 保持原样,MVP 不动它们。

## 流水线执行顺序

执行查询时(见 `ThriftServer.executeSparkSql`):
1. `SparkSqlParser.parseStatement` -> `SingleStatementContext`
2. `SparkAstBuilder.buildStatement` -> IR `Relation`
3. `SemanticAnalyzer.analyze(rel)` -> 填 `TableScan.output` + 给 `AttributeReference`/`Literal` 设 DataType
4. `RewriteEngine.rewrite(rel)` -> 原地重命名函数(`size`->`array_length` 等)
5. `DuckDbSqlGenerator.generate` -> DuckDB SQL 串
6. `DuckDbExecutor.execute` -> `QueryResult`

## SQL 生成约定(不要破坏)

`DuckDbSqlGenerator` 把 relation 拆成两类:
- **Query 节点**(`Project` / `Filter` / `Aggregate` / `Sort` / `Limit` / `Union` / `With` / `Generate`)- 发完整的 `SELECT ...`。当它们作为 `FROM` 子句的 source 时,**必须**通过 `emitChildSource` 加 `(...)` 包裹。
- **Source 节点**(`TableScan` / `Join` / `Values` / `SubqueryAlias`)- 直接发表表达式,不加 `SELECT *` 前缀。`Join` 直接发 `left JOIN right ON ...`;`SubqueryAlias` 发 `(query) AS alias`。

`emitJoin` **不**加 `SELECT * FROM` 前缀 - 之前是 bug。`FROM` 子句必须走 `emitChildSource`,永远不要裸用 `emit`。

`DuckDbSqlGenerator` 是**无状态**的。**禁止**:
- 查 Catalog
- 解析名称(标识符解析是 Analyzer 的活)
- 重写表达式

它的唯一职责是 IR -> SQL 序列化。Generator 默认 IR 已被 Analyzer 解析过。

## Analyzer 职责(名字解析的唯一归属)

Analyzer 负责:
- **标识符解析** - `SELECT name FROM user` 里 `name` 到底是 `user.name` 还是 `dept.name`,由 Analyzer 解析,Generator 不查 Catalog。
- **类型推导** - 给 `AttributeReference` 设 DataType(用 `IrNode.setDataType`)。
- **函数解析** - 函数签名/重载。
- **Catalog 查询** - 把 `TableScan.output` 填上真实列。

Generator 永远不用查 Catalog。

## 函数映射归属(避免双重映射)

当前 MVP:**函数映射由 `DuckDbSqlGenerator.FN_MAP` 独占**(例如 `size` -> `array_length`,`explode` -> `unnest`)。

`RewriteEngine` 也有 rename 逻辑,但在它**正式接入执行流水线之前**,不要去改它的 rename 表 - 否则会出现两个地方同时映射。接入后考虑把 `FN_MAP` 删掉,由 Rewrite 统一负责。

## 已知 MVP 缺口

- DDL 未实现(CREATE / ALTER / DROP 是 Phase 2)
- `Aggregate.aggregateExpressions` 被重载成整个 select list(分组列 + 聚合在一起)- 不是干净的 Spark 风格拆分
- 命名窗口(`WINDOW w AS ...`)和聚合 `FILTER` 子句抛 `UnsupportedOperationException`
- Catalog 当前是内存 `LinkedHashMap`(MVP);后续换 DuckDB-backed store,**不改调用方**

## TPC-DS 统一测试

`TpcdsQueryPipelineTest` 参数化跑每个查询的完整流水线(parse -> analyze -> rewrite -> generate),不执行。两组资源:
- `localsql-thrift/src/test/resources/tpcds-v2.7.0/` - 32 个查询,apache/spark `a2da2926` 的 `sql/core/src/test/resources/tpcds-v2.7.0`
- `localsql-thrift/src/test/resources/tpcds/` - 103 个查询,apache/spark `master` 的 `sql/core/src/test/resources/tpcds`(完整 q1..q99 + a/b 变体)

MVP 未支持特性的查询进 `UNSUPPORTED` 列表并跳过(当前为空,136/136 全部翻译成功),防止回归。窗口函数(`OVER` 含 `ROWS BETWEEN` frame)和 ROLLUP/CUBE/GROUPING SETS 已实现:`FunctionCall.windowSpec`(可变,挂 `WindowSpec`)、`Aggregate.groupingAnalytics`(`ROLLUP`/`CUBE`/`GROUPING SETS`,含 legacy `WITH ROLLUP` 形式)。命名窗口(`WINDOW w AS ...`)和聚合 `FILTER` 子句仍显式抛 `UnsupportedOperationException`。

### 分层测试架构

- **L1 翻译回归** - `TpcdsQueryPipelineTest`:parse -> analyze -> rewrite -> generate,不执行
- **L3 结果一致性(手工 golden)** - `TpcdsGoldenResultTest`:走真实 Thrift 端点执行,结果与 Spark 语义的手工校准期望对比
- **L3 结果一致性(差分 oracle)** - `TpcdsConsistencyTest`:**135/135 全过**。同一份数据(DuckDB `tpcds` 扩展 `dsdgen(sf=0.01)` 生成全 24 表)+ 同一条 SQL:期望侧 DuckDB 原生执行原文(反引号归一化为双引号),实际侧走 Thrift 管线,双向 `EXCEPT ALL` 严格多重集对比。任何翻译语义漂移都会暴露

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
- transaction / ACID / snapshot
- distributed execution
- DDL(CREATE / ALTER / DROP 是 Phase 2)
- 权限/认证

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

1. **`Catalog` 模块保持纯(不依赖 DuckDB)** - 用 `CatalogStore` 接口做持久化抽象,在 `localsql-duckdb` 模块里实现 `DuckDbCatalogStore`。
2. **`Catalog` 当前是内存缓存**(`LinkedHashMap`),`CatalogService` 持有 `CatalogStore` 时,启动从 store load,写入同时落 store + cache。
3. **TableScan.output 必须可变** - 当前 `TableScan` 的 `output` 字段是 `final` + `List.copyOf()`,Analyzer 没法把查到的列塞回去。改成可变 + 加 `setOutput` 方法。
4. **Analyzer 修复** - `visitTableScan` 当前查 Catalog 后**丢弃**了结果(`out` list 没赋回),要改成真的 `r.setOutput(out)`。同时给 `AttributeReference` 设 DataType(用 `IrNode.setDataType`)。
5. **RewriteEngine 接入** - 它已经会 `rename` 函数名(`size` -> `array_length` 等),接进去就行;`FN_MAP` 后续可考虑删掉(避免双重映射)。
6. **`thrift` pom 需加** `localsql-analyzer` 和 `localsql-rewrite` 两个依赖。
7. **ThriftServer / LocalSqlThriftService 的 ExecuteStatement 都要接入** - 两处当前都直接 parse -> generate,要插入 analyze + rewrite。
8. **Main 重写** - 现在 `Main` 同时手动调 `catalogService.registerSampleTable`(Catalog)+ `executor.registerSampleTable`(DuckDB 数据),数据重复。改成:`DuckDbCatalogStore` 初始化 -> 注册表时同时写元数据表 + 建 DuckDB 数据表 + 写 `runtime_table`。
9. **SmokeTest 同步更新** - `ThriftServerSmokeTest` 现在直接用 `CatalogService` + `DuckDbExecutor`,改成用 `DuckDbCatalogStore`。

### 进度

| 状态 | 文件 | 说明 |
|------|------|------|
| ✅ | `localsql-catalog/.../Catalog.java` | `Table` / `Column` record 富化:加 `tableId`、`tableType`、`properties`、`metadataJson`、`ordinal`、`defaultValue`、`expression`;保留旧构造器做向后兼容;加 `parseDataType` / `toStorageType` 工具方法 |
| ✅ | `localsql-catalog/.../CatalogStore.java` | 新建。`init()` / `saveTable()` / `loadTable()` / `loadTables()` / `saveRuntimeInfo()` / `loadAllRuntimeInfo()` |
| ✅ | `localsql-catalog/.../CatalogService.java` | 加 `(CatalogStore)` 构造器,启动时 `loadFromStore`;`registerTable` 落 store + cache;保留 `registerSampleTable` 向后兼容 |
| ✅ | `localsql-duckdb/.../DuckDbCatalogStore.java` | 已建。实现 `CatalogStore`,`init()` 建 4 张表(`catalog_table`/`catalog_column`/`catalog_property`/`runtime_table`),`saveTable`/`loadTable`/`loadTables`/`saveRuntimeInfo`/`loadAllRuntimeInfo` 全部实现;用 `executor.execute`/`executeUpdate` 操作 |
| ✅ | `localsql-ir/.../TableScan.java` | `output` 去掉 `final`,改为可变 `ArrayList`,加 `setOutput(List<AttributeReference>)` 方法 |
| ✅ | `localsql-analyzer/.../SemanticAnalyzer.java` | `visitTableScan` 真正 `r.setOutput(out)`;按 column.type() 给 AttributeReference `setDataType`(qualifier 优先用 alias) |
| ✅ | `localsql-thrift/pom.xml` | 加了 `localsql-analyzer` 和 `localsql-rewrite` 两个 dependency |
| ✅ | `localsql-thrift/.../ThriftServer.java` | `executeSparkSql` 在 `buildStatement` 之后、`generator.generate` 之前调 `analyzer.analyze(rel)` + `rewriter.rewrite(rel)`;构造器内部 new `SemanticAnalyzer` + `RewriteEngine` |
| ✅ | `localsql-thrift/.../LocalSqlThriftService.java` | `ExecuteStatement` 同样接入 analyzer + rewrite |
| ✅ | `localsql-app/.../Main.java` | 用 `DuckDbCatalogStore` + `DuckDbExecutor`,`registerTable` 一次完成元数据 + 数据 + runtime_table 写入;data 里的 `age` / `amount` / `id` 改成正确类型(不再全 VARCHAR) |
| ✅ | `localsql-thrift/.../ThriftServerSmokeTest.java` | 用 `DuckDbCatalogStore` |
| ✅ | 跑 `mvn test` 全部通过 | **已验证**。`mvn clean test` 2/2 通过;`mvn clean package -DskipTests` 通过;`runtime.jar` demo 跑出正确结果 |

### 下次从这里继续

DuckDB 元数据 + Analyzer/RewriteEngine 接入**已全部完成**(见上表 ✅)。WHERE/ORDER BY/LIMIT、HAVING、子查询别名已有端到端回归覆盖。下一步方向:窗口函数、实现 DDL、或接更多 Thrift 元数据接口。

`CatalogStore` 接口已定,`DuckDbCatalogStore` 实现时直接 `implements CatalogStore` 即可。`init()` 里发 `CREATE TABLE IF NOT EXISTS catalog_table ...` 等 4 条 DDL。`loadTable(database, tableName)` 用 `SELECT * FROM catalog_table JOIN catalog_column USING (table_id) WHERE database_name = ? AND table_name = ? ORDER BY ordinal` 然后手动组 `Catalog.Table`。

`DuckDbExecutor` 已有 `executeQuery(String) -> QueryResult` 和 `executeUpdate(String) -> int`,直接复用。

## 约定

- 没有明确要求时不加代码函数注释，仅添加类注释
- IR 节点类:一个文件一个 public 顶层类(Java 对 record / sealed member 的硬性要求 - `DataType.java` 只放 sealed interface,具体类型各自单独文件放在 `ir/type/`)
- `FunctionCall.name` 是可变的(通过 `rename()`),专门为 rewrite 层原地改名字设计
- `Main` 样例数据用 VARCHAR - DuckDB 会做强转
