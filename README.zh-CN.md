# LocalSQL

中文 | **[English](./README.md)**

LocalSQL 是面向开发者和 AI Agent 的嵌入式 SQL Sandbox：解析 Spark SQL 3.2.0（或按连接选择 StarRocks 3.x 方言的 DQL），经 Common IR 翻译后在进程内 DuckDB 执行，并提供 HiveServer2 兼容的 Thrift 接口。无需部署 Spark、Hadoop 或 Hive 服务。

核心理念：生产环境用于执行，LocalSQL 用于本地实验。

## 当前状态

更新日期：2026-11-12。查询执行、基础 DML/DDL、文件数据库、Java UDF 接入路径与 StarRocks 方言前端（M0–M4）已存在。**类型传播、UDF 打包运行、Thrift 行为和失败恢复仍有正确性缺口，目前不能把 LocalSQL 当作 Spark/StarRocks 语义一致性验证器。**

下一阶段优先修正确性和模块边界，范围、迁移顺序与验收标准见[开发计划](docs/development-plan.md)。计划是待实施方案，不代表代码修复已经完成。

### 已有实现路径

- 基于 Spark 3.2.0 grammar 的解析，关键字大小写不敏感，支持反引号标识符。解析成功不代表执行语义已支持。
- StarRocks 方言前端（M0–M4）：连接时以 `;sql_dialect=starrocks` 指定，基于 verbatim 的 StarRocks 3.5.21 语法（仅 DQL），产出同一 IR。方言作用域的 strict 函数目录与 rewrite、管理语句（`USE`/`SHOW CATALOGS`/`SET CATALOG`）、`EXPLAIN` 返回 DuckDB 译文。见[兼容矩阵](docs/starrocks-compat.md)。
- DQL：SELECT、DISTINCT、JOIN、WHERE、GROUP BY、HAVING、ORDER BY、整数字面量 LIMIT。
- CTE、子查询、UNION/INTERSECT/EXCEPT、窗口函数、ROLLUP/CUBE/GROUPING SETS。
- INSERT INTO/OVERWRITE；CREATE TABLE（CTAS/LIKE/PARTITIONED BY）；CREATE/DROP VIEW；CREATE/ALTER/DROP DATABASE；表列变更、重命名及表属性操作。
- HiveServer2 连接、查询、结果获取与元数据接口，以及 `ThriftServer.executeSparkSql` 直接嵌入式执行入口。
- 内存 Catalog，可选 DuckDB 表元数据持久化；物理文件数据库保存与加载。
- 本地 DDL 文件导入、函数配置；Java UDF/GenericUDF/GenericUDTF 的进程内执行，不是独立隔离的 worker 服务。
- TPC-DS 翻译和 DuckDB 差分测试，以及函数、DDL/DML、UDF 回归样例。

### 已知限制

- DQL 严格名称解析已落地（分层 Scope；错库/错表/缺列与歧义引用在执行前拒绝；Star 展开、别名可见性、lambda 绑定），但类型传播仍不完整，未知类型会回落默认值。
- DDL 失败可回滚内存 Catalog；提交失败「状态未知」的 store 重载恢复仍待完成（F2 收尾）。
- **正式 `runtime.jar --udf-config ...` 因缺少 Hive 运行类而失败（F3 残留）。**
- Thrift：GetResultSetMetadata 仍全列报 STRING、GetOperationStatus 一律 FINISHED、无效 session 回落 default、CloseOperation 不校验句柄（F5 残留）。
- StarRocks 前端仅 DQL：模型 DDL（`PRIMARY KEY`/`DISTRIBUTED BY`）、INSERT/UPDATE/DELETE 与 UDF 路径显式拒绝；`||` 固定为拼接语义（sql_mode=34）。
- DELETE/UPDATE/MERGE、multi-INSERT、NATURAL JOIN、命名窗口、聚合 FILTER 不支持。脚本 TRANSFORM 查询不支持；部分高阶 Lambda 函数已实现，限制见[函数说明](docs/functions.md)。
- 不做优化器、成本模型、分布式执行或权限认证，不提供用户级事务/ACID 保证；部分 DDL/DML 已使用内部事务。

已有路径和通过的样例不保证所有 SQL 组合或 Spark 边界语义正确；本地样本也不能证明生产规模下的行为。

## 快速开始

### 环境与构建

使用 JDK 21、Maven 3.9+。同时检查下面两个版本命令，Maven 可能使用与 `java` 不同的 JDK。

```bash
java -version
mvn -version
mvn clean package -DskipTests
```

产物：`localsql-app/target/runtime.jar`。

```bash
# 默认 10000 端口；可用位置参数覆盖端口
java -jar localsql-app/target/runtime.jar
java -jar localsql-app/target/runtime.jar 10001

# 打开已有文件数据库；名称解析仍受当前缺陷限制
java -jar localsql-app/target/runtime.jar --load sandbox.duckdb --schema default
```

Demo 创建带真实类型的 `users` 和 `orders` 样例表；进程持续运行，停止后退出。

### 连接与查询

在 DBeaver、DataGrip 或其他兼容客户端中选择 Hive JDBC 数据源：

```text
jdbc:hive2://localhost:10000/default                         # Spark SQL 方言（默认）
jdbc:hive2://localhost:10000/default;sql_dialect=starrocks   # StarRocks 方言（仅 DQL）
```

MVP 不启用认证。元数据、类型和生命周期仍受已知限制约束，不代表任意客户端功能均已验证。

```sql
SELECT u.name, count(*) AS cnt
FROM users u
JOIN orders o ON u.id = o.user_id
GROUP BY u.name
ORDER BY cnt DESC;
```

样例数据返回 `alice, 2` 和 `bob, 1`。

`Main` 另有 `--import-ddl`、`--out`、`--manifest`、`--failures`、`--udf-config` 和 `--function-map` 参数；这些是现有入口，不代表导入/UDF 缺陷已修复。详细操作见[使用指南](docs/usage.md)。

## 架构

普通查询路径为 ParseTree → Common IR → Analyzer → Rewrite → DuckDB SQL → Executor。Spark 与 StarRocks 两个前端产出同一 IR，下游 Analyzer/Rewrite/Generator/Executor 方言无感知；`SqlPipeline` 编排 DDL/DML 和可选 UDF 分阶段执行；SHOW/DESCRIBE/USE 按方言优先分发。

### 目标边界

1. 模块依赖明确，后端不直接读取 Spark/ANTLR tree，IR 是唯一查询表示。
2. Parser 只产 ParseTree，Spark AST Builder 负责转换为 IR。
3. Analyzer 独占作用域、名称、类型、函数绑定及 Catalog 查询。
4. Rewrite 独占表达式/函数降级，Generator 只序列化已解析、已降级 IR。
5. Executor 只执行 SQL 并返回结果，不读取 IR。
6. Catalog 只存逻辑元数据，不包含 JDBC 连接、物理 SQL 或 runtime 状态。
7. Runtime 编排协调事务、物理名字绑定与 UDF 阶段。

函数转换已由 Rewrite 独占、Generator 只序列化 IR（A1）；CatalogService 不再修补 runtime SQL（A2）。剩余偏离是 Analyzer 类型传播不完整——这是待修复问题，不能作为新代码照搬的例外。StarRocks 前端示范了新方言的接入模式：语法 + AST builder 产出同一 IR，方言专属语法显式拒绝。

### 模块

| 模块 | 当前职责 |
|---|---|
| `localsql-parser` | ANTLR 语法（Spark 3.2.0 SqlBase、StarRocks 3.5.21）与解析入口 |
| `localsql-ir` | 公共关系、表达式、语句和类型表示 |
| `localsql-spark` | Spark ParseTree → IR |
| `localsql-starrocks` | StarRocks ParseTree → IR（DQL-only 镜像 builder，方言归一） |
| `localsql-analyzer` | DQL 严格名称解析与语句检查；类型传播待完善 |
| `localsql-rewrite` | 函数改名、表达式降级与 UDF/UDTF 查询拆分（函数转换唯一归属） |
| `localsql-catalog` | 逻辑元数据、缓存、CatalogStore；runtime 职责待分离 |
| `localsql-duckdb` | SQL 生成、JDBC 执行、元数据 store、内置 Java 函数 |
| `localsql-udf-api` | UDF 定义、注册表、批数据/Gateway 契约 |
| `localsql-udf-runtime` | 进程内 Hive UDF 适配；产物运行依赖待修复 |
| `localsql-thrift` | SQL 编排与 HiveServer2 RPC 服务 |
| `localsql-app` | 入口、样例、DDL 导入、配置和可执行 JAR |

## 测试

```bash
# 全量：需要允许端口监听；TPC-DS 执行还需要对应扩展
mvn clean test

# 135 份 SQL + 1 个汇总测试，不执行查询
mvn -pl localsql-thrift -am test \
  -Dtest=TpcdsQueryPipelineTest -Dsurefire.failIfNoSpecifiedTests=false

# DuckDB 原生执行与 Thrift 管线的差分
mvn -pl localsql-thrift -am test \
  -Dtest=TpcdsConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false

# StarRocks 前端翻译回归 / 双方言孪生差分
mvn -pl localsql-thrift -am test \
  -Dtest=StarRocksFrontendPipelineTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl localsql-thrift -am test \
  -Dtest=StarRocksTwinDifferentialTest -Dsurefire.failIfNoSpecifiedTests=false
```

- `TpcdsQueryPipelineTest`：翻译回归，包含 Analyzer/Rewrite 调用与 strict 24 表 Catalog fixture。
- `TpcdsGoldenResultTest`：真实 Thrift 执行与手工校准的期望结果比较。
- `TpcdsConsistencyTest`：DuckDB 原生执行与 Thrift 结果字符串化后比较多重集；oracle 不可用的查询只检查执行成功。不验证结果类型、行顺序或 Spark 特有语义。
- `StarRocksFrontendPipelineTest`：官方 StarRocks TPC-DS 基准语料（103 份）经 StarRocks 前端翻译，strict 函数绑定。
- `StarRocksTwinDifferentialTest`：同一 SQL 文本在相同 `dsdgen` 数据上由 Spark 与 StarRocks 会话各执行一次，双向 `EXCEPT ALL` 必须为空；8 份双引号别名查询 Spark 语法本就拒绝，仅断言 StarRocks 侧执行成功。

**审查基线（2026-11-12）：** Java 21 打包成功；排除三个依赖端口监听的 L3 测试类（`TpcdsConsistencyTest`/`TpcdsGoldenResultTest`/`StarRocksTwinDifferentialTest`）后 733 项测试通过；StarRocks L1 104 项与孪生差分 103 项在端口可用且装有 DuckDB tpcds 扩展时全绿。测试数量是带日期的快照，不是覆盖率或一致性保证。

## 开发方向

近期以[开发计划](docs/development-plan.md)为准：

- 收尾 F3 残留（正式 JAR 的 UDF 配置入口缺 Hive 运行依赖）与 F2（提交失败「状态未知」的 store 重载恢复）。
- 修复 Thrift 遗留行为（结果类型元数据、Operation 状态、session/句柄校验）。
- 继续 Analyzer 的已解析 IR 校验闸门与类型传播。

长期方向包括样本数据导入/生成、本地批量 SQL 回归 CLI/API 和可复现数据集。AI 自动生成测试、生产风险分析、审批与生产提交仍是设想，不是已交付功能；多引擎和优化器不作为下一阶段主线。

## 文档与贡献

- [English](README.md)
- [使用指南](docs/usage.md)
- [StarRocks 兼容矩阵](docs/starrocks-compat.md)：三态矩阵与验证证据
- [StarRocks 方言设计](docs/starrocks-compat-plan.md)
- [开发计划](docs/development-plan.md)：范围、修复方案和验收标准
- [函数说明](docs/functions.md)
- [UDF 实现/设计说明](docs/udf-plan.md)
- [AGENTS.md](AGENTS.md)：架构约束与协作指南

提交前遵循 AGENTS.md，补回归测试并使用 Java 21 验证。在允许端口监听的环境运行全量/Thrift 测试，明确列出未验证项。计划项只有在实现和验收完成后才能标记为已完成。

## 许可证

Apache License 2.0
