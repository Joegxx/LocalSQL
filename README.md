# LocalSQL

在本地运行 Spark SQL，无需部署 Spark、Hadoop 或 Hive。

Run Spark SQL locally without deploying Spark, Hadoop, or Hive.

> 使用指南（构建、启动、JDBC 客户端连接、SQL 支持范围、FAQ）：[docs/usage.md](docs/usage.md)
>
> User guide (build, run, JDBC client setup, SQL coverage, FAQ): [docs/usage.md](docs/usage.md)（中文）

LocalSQL 是一个基于 Java 21 的嵌入式 SQL Runtime。它使用 Spark SQL 3.2.0 语法解析查询，将查询转换为独立的 Common IR，再生成 DuckDB SQL 并在进程内执行。LocalSQL 同时提供 HiveServer2 Thrift 接口，可供 JDBC 客户端和数据库 IDE 连接。

LocalSQL is a Java 21 embedded SQL runtime. It parses Spark SQL 3.2.0 syntax, converts queries into an independent Common IR, generates DuckDB SQL, and executes it in-process. It also exposes a HiveServer2 Thrift endpoint for JDBC clients and database IDEs.

## 中文

### 项目目标

LocalSQL 面向需要在开发机、测试环境或嵌入式应用中运行数仓 SQL 的场景：

- 本地验证 Spark SQL，无需启动完整的大数据集群
- 使用 DuckDB 提供轻量、进程内的物理执行能力
- 通过 HiveServer2 Thrift 协议兼容常见 JDBC 客户端
- 使用公共 IR 隔离 Spark Parser 与执行后端
- 为后续扩展 Analyzer、Rewrite、DDL 和其他执行后端保留清晰边界

当前版本是 MVP，重点支持 DQL 查询，不包含分布式执行、事务、权限和优化器。

### 执行流程

```text
Spark SQL
   -> ANTLR ParseTree
   -> Common IR
   -> Semantic Analyzer
   -> Rewrite Engine
   -> DuckDB SQL
   -> DuckDB Executor
   -> Query Result
```

IR 是各模块之间的唯一查询表示。DuckDB 后端只消费 IR，不直接依赖 Spark ParseTree 或 ANTLR Tree。

### 当前能力

- Spark SQL 3.2.0 ANTLR 语法解析
- SELECT、WHERE、JOIN、GROUP BY、HAVING、ORDER BY、LIMIT
- 表别名和子查询别名
- UNION、CTE、VALUES、常用表达式和函数
- Catalog 表和列元数据
- DuckDB 持久化 CatalogStore
- HiveServer2 `TCLIService` Thrift RPC
- `OpenSession -> ExecuteStatement -> FetchResults` 查询链路
- `GetTables`、`GetColumns` 等元数据接口

端到端测试覆盖 JOIN/GROUP BY、WHERE/ORDER BY/LIMIT、HAVING、子查询别名和 Thrift 元数据访问。

### 环境要求

- JDK 21
- Maven 3.9+

确认 Java 版本：

```bash
java -version
mvn -version
```

### 构建和测试

运行全部测试：

```bash
mvn clean test
```

构建可执行 JAR：

```bash
mvn clean package -DskipTests
```

生成文件：

```text
localsql-app/target/runtime.jar
```

### 启动

默认监听 HiveServer2 Thrift 端口 `10000`：

```bash
java -jar localsql-app/target/runtime.jar
```

指定端口：

```bash
java -jar localsql-app/target/runtime.jar 10001
```

启动后会注册 `users` 和 `orders` 示例表，并执行一条 JOIN/GROUP BY 演示查询。服务会持续运行，直到进程退出。

### JDBC 连接

使用兼容 HiveServer2 的 JDBC 客户端连接：

```text
jdbc:hive2://localhost:10000/default
```

当前 MVP 不启用认证。可在 DBeaver、DataGrip 或其他支持 Hive JDBC 的工具中使用该地址。

示例查询：

```sql
SELECT u.name, count(*) AS cnt
FROM users u
JOIN orders o ON u.id = o.user_id
GROUP BY u.name
ORDER BY cnt DESC;
```

预期结果：

```text
alice  2
bob    1
```

### 模块结构

| 模块 | 职责 |
| --- | --- |
| `localsql-parser` | ANTLR 语法和 Spark SQL ParseTree |
| `localsql-ir` | 与 Parser 和执行后端解耦的 Common IR |
| `localsql-spark` | Spark ParseTree 到 Common IR |
| `localsql-analyzer` | 标识符解析、类型推导和 Catalog 查询 |
| `localsql-rewrite` | IR 重写和函数名转换 |
| `localsql-catalog` | 逻辑数据库、表、列和 CatalogStore 抽象 |
| `localsql-duckdb` | DuckDB SQL 生成、执行和 CatalogStore 实现 |
| `localsql-thrift` | HiveServer2 Thrift 服务和查询编排 |
| `localsql-app` | 应用入口、示例数据和可执行 JAR |

### 当前限制

- 仅支持 DQL；CREATE、ALTER、DROP 尚未实现
- ROLLUP、CUBE、GROUPING SETS 尚未实现
- 窗口函数覆盖仍在完善
- 不包含优化器、统计信息和成本模型
- 不支持事务、ACID、权限和认证
- 不支持分布式执行

### 开发说明

- 必须使用 Java 21 构建
- Parser 只负责解析，Analyzer 负责解析名称和类型，Generator 只负责序列化 IR
- 所有执行后端必须消费 Common IR，不得直接读取 Spark ParseTree
- Spark 3.2.0 grammar 中的 `LETTER` 规则保留了小写字母支持，不要恢复为仅 `[A-Z]`
- 完整协作和架构约束见 `AGENTS.md`

## English

### Goals

LocalSQL targets development, testing, and embedded scenarios where warehouse SQL needs to run without a full data platform:

- Validate Spark SQL locally without starting a big-data cluster
- Use DuckDB as a lightweight in-process execution engine
- Connect JDBC clients through the HiveServer2 Thrift protocol
- Isolate the Spark parser from execution backends through a Common IR
- Preserve clear boundaries for analyzers, rewrites, DDL, and future backends

The current release is an MVP focused on DQL. Distributed execution, transactions, authorization, and query optimization are out of scope.

### Execution Pipeline

```text
Spark SQL
   -> ANTLR ParseTree
   -> Common IR
   -> Semantic Analyzer
   -> Rewrite Engine
   -> DuckDB SQL
   -> DuckDB Executor
   -> Query Result
```

The Common IR is the only query representation shared across modules. The DuckDB backend consumes IR and never reads Spark ParseTree or ANTLR trees directly.

### Current Features

- Spark SQL 3.2.0 ANTLR grammar
- SELECT, WHERE, JOIN, GROUP BY, HAVING, ORDER BY, and LIMIT
- Table aliases and subquery aliases
- UNION, CTE, VALUES, common expressions, and functions
- Table and column metadata through the Catalog
- DuckDB-backed CatalogStore persistence
- HiveServer2 `TCLIService` Thrift RPC
- `OpenSession -> ExecuteStatement -> FetchResults` query flow
- Metadata operations including `GetTables` and `GetColumns`

End-to-end tests cover JOIN/GROUP BY, WHERE/ORDER BY/LIMIT, HAVING, subquery aliases, and Thrift metadata access.

### Requirements

- JDK 21
- Maven 3.9+

Verify the toolchain:

```bash
java -version
mvn -version
```

### Build and Test

Run all tests:

```bash
mvn clean test
```

Build the executable JAR:

```bash
mvn clean package -DskipTests
```

The application is generated at:

```text
localsql-app/target/runtime.jar
```

### Run

Start the HiveServer2 Thrift service on the default port `10000`:

```bash
java -jar localsql-app/target/runtime.jar
```

Use a custom port:

```bash
java -jar localsql-app/target/runtime.jar 10001
```

At startup, LocalSQL registers sample `users` and `orders` tables and runs a JOIN/GROUP BY demonstration query. The process keeps serving requests until it is terminated.

### JDBC Connection

Connect with a HiveServer2-compatible JDBC client:

```text
jdbc:hive2://localhost:10000/default
```

Authentication is disabled in the current MVP. The URL can be used with DBeaver, DataGrip, or another client that supports Hive JDBC.

Example query:

```sql
SELECT u.name, count(*) AS cnt
FROM users u
JOIN orders o ON u.id = o.user_id
GROUP BY u.name
ORDER BY cnt DESC;
```

Expected result:

```text
alice  2
bob    1
```

### Modules

| Module | Responsibility |
| --- | --- |
| `localsql-parser` | ANTLR grammar and Spark SQL ParseTree |
| `localsql-ir` | Common IR independent of parsers and backends |
| `localsql-spark` | Spark ParseTree to Common IR conversion |
| `localsql-analyzer` | Name resolution, type inference, and Catalog lookup |
| `localsql-rewrite` | IR rewrites and function-name translation |
| `localsql-catalog` | Logical databases, tables, columns, and CatalogStore API |
| `localsql-duckdb` | DuckDB SQL generation, execution, and CatalogStore implementation |
| `localsql-thrift` | HiveServer2 Thrift service and query orchestration |
| `localsql-app` | Application entry point, sample data, and executable JAR |

### Limitations

- DQL only; CREATE, ALTER, and DROP are not implemented
- ROLLUP, CUBE, and GROUPING SETS are not implemented
- Window-function coverage is still in progress
- No optimizer, statistics, or cost model
- No transactions, ACID guarantees, authorization, or authentication
- No distributed execution

### Development Notes

- Java 21 is required
- The parser only parses, the analyzer resolves names and types, and generators only serialize IR
- Every execution backend must consume the Common IR instead of Spark ParseTree
- Keep the lowercase-letter support in the Spark 3.2.0 grammar `LETTER` rule; do not restore it to `[A-Z]` only
- See `AGENTS.md` for detailed architecture and collaboration rules

## License

No license has been declared yet.
