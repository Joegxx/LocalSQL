# LocalSQL 使用指南

LocalSQL 是一个内嵌的 Spark SQL 运行时:用 Spark SQL 3.2.0 语法写查询,翻译后在进程内 DuckDB 上执行,并通过标准 HiveServer2 Thrift 协议对外提供服务。无需部署 Spark/Hadoop/Hive,任何支持 Hive JDBC 的客户端都能直接连接。

## 目录

- [架构一览](#架构一览)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [用数据库客户端连接](#用数据库客户端连接)
- [用代码连接](#用代码连接)
- [内置样例数据](#内置样例数据)
- [支持的 SQL 特性](#支持的-sql-特性)
- [显式不支持的特性](#显式不支持的特性)
- [常见问题排查](#常见问题排查)
- [运行测试](#运行测试)

---

## 架构一览

```
Spark SQL 文本
   |
   v
ANTLR 解析 (Spark 3.2.0 SqlBase 语法)
   |
   v
Common IR (唯一真相源)
   |
   v
Analyzer   (Catalog 元数据填充、类型推导)
   |
   v
Rewrite    (Spark -> DuckDB 函数名映射)
   |
   v
DuckDB SQL 生成
   |
   v
DuckDB 执行 (进程内)
   |
   v
Thrift 返回结果 (HiveServer2 TCLIService, 端口 10000)
```

关键设计:关键字大小写不敏感(`select` 和 `SELECT` 等价),字符串字面量保持原样;IR 是各模块间唯一查询表示,执行后端不直接读 ParseTree。

## 环境要求

- JDK 21(必须,`maven.compiler.release=21`)
- Maven 3.9+

确认环境:

```bash
java -version    # 应输出 21.x
mvn -version
```

## 快速开始

### 1. 构建

```bash
mvn clean package -DskipTests
```

产物是 `localsql-app/target/runtime.jar`( shaded 可执行 jar )。

### 2. 启动服务

```bash
# 默认监听 10000 端口
java -jar localsql-app/target/runtime.jar

# 指定端口(如 10001)
java -jar localsql-app/target/runtime.jar 10001
```

启动日志会显示:

```
LocalSQL Embedded Spark SQL Runtime starting...
HiveServer2 Thrift server listening on port 10000 (jdbc:hive2://localhost:10000)
Demo query: SELECT u.name, count(*) AS cnt FROM users u JOIN orders o ...
Result columns: [name, cnt]
  [alice, 2]
  [bob, 1]
```

看到 demo 结果和端口监听日志即启动成功。进程持续运行,`Ctrl+C` 退出。

### 3. 验证端口

```bash
# Thrift 端口应处于 LISTEN 状态
lsof -iTCP:10000 -sTCP:LISTEN
```

## 用数据库客户端连接

连接参数与连 HiveServer2 完全一致:

| 参数 | 值 |
|---|---|
| Host | `localhost` |
| Port | `10000` |
| JDBC URL | `jdbc:hive2://localhost:10000/default` |
| 用户/密码 | 任意(当前不启用认证) |

### DBeaver

1. 新建连接 -> 选择 **Apache Hive**
2. JDBC URL 填 `jdbc:hive2://localhost:10000/default`(如无 Hive 驱动,按提示下载)
3. 测试连接 -> 连接
4. 左侧树可浏览数据库/表/列元数据;SQL 编辑器里直接写 Spark SQL 执行

### DataGrip / IntelliJ Database 面板

1. 新建数据源 -> **Apache Hive**
2. URL: `jdbc:hive2://localhost:10000/default`,用户名任意
3. Schema 选择 `default`,即可看到 `users` / `orders` 样例表

### beeline(如有 Hadoop 环境)

```bash
beeline -u jdbc:hive2://localhost:10000/default -n anyuser
```

## 用代码连接

任何 Hive JDBC 驱动都可直连:

```java
Class.forName("org.apache.hive.jdbc.HiveDriver");
try (Connection conn = DriverManager.getConnection("jdbc:hive2://localhost:10000/default", "anyuser", "")) {
    try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
            "SELECT u.name, count(*) AS cnt FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.name")) {
        while (rs.next()) System.out.println(rs.getString(1) + " " + rs.getLong(2));
    }
}
```

也可以不走 Thrift、直接在 JVM 内嵌使用(参考 `ThriftServer.executeSparkSql`):

```java
DuckDbExecutor executor = new DuckDbExecutor();
CatalogService catalog = new CatalogService(new DuckDbCatalogStore(executor));
ThriftServer server = new ThriftServer(catalog, executor);
var result = server.executeSparkSql("SELECT count(*) FROM users");  // QueryResult(columns, rows)
```

## 内置样例数据

`runtime.jar` 启动时自动注册两张表(元数据进 Catalog,数据进 DuckDB):

**users**

| id (BIGINT) | name (VARCHAR) | age (INT) |
|---|---|---|
| 1 | alice | 30 |
| 2 | bob | 25 |
| 3 | carol | 40 |

**orders**

| id (BIGINT) | user_id (BIGINT) | amount (DOUBLE) |
|---|---|---|
| 100 | 1 | 99.5 |
| 101 | 1 | 20.0 |
| 102 | 2 | 150.0 |

试一试:

```sql
SELECT u.name, count(*) AS cnt
FROM users u JOIN orders o ON u.id = o.user_id
GROUP BY u.name HAVING count(*) >= 1
ORDER BY cnt DESC;
```

## 支持的 SQL 特性

以下特性均已通过与 DuckDB 原生执行的差分一致性验证(TPC-DS 135 个查询全量对比):

- **基本查询**:`SELECT` / `FROM` / `WHERE` / `GROUP BY` / `HAVING` / `ORDER BY` / `LIMIT`
- `SELECT DISTINCT`
- **JOIN**:`INNER` / `LEFT` / `RIGHT` / `FULL OUTER` / `CROSS` / `SEMI` / `ANTI`,以及 `JOIN ... USING (col)`
- **集合操作**:`UNION [ALL]` / `INTERSECT` / `EXCEPT`
- **CTE**:`WITH a AS (...), b AS (...) SELECT ...`
- **子查询**:标量子查询、`EXISTS` / `NOT EXISTS`、`IN (子查询)`、派生表(含别名)
- **窗口函数**:`OVER (PARTITION BY ... ORDER BY ...)` 及 `ROWS|RANGE BETWEEN` frame(`UNBOUNDED PRECEDING/FOLLOWING`、`CURRENT ROW`、数值偏移)
- **分组扩展**:`ROLLUP` / `CUBE` / `GROUPING SETS`(含 legacy `GROUP BY a,b WITH ROLLUP`)
- **CASE WHEN**、`IN` 列表、`BETWEEN`、`LIKE` / `RLIKE`、`IS [NOT] NULL`
- **操作符**:`+ - * / %`、整数除法 `DIV`(-> `//`)、位运算 `& | ^`、字符串 `||`、空安全相等 `<=>`(`IS NOT DISTINCT FROM`)
- **CAST**:含 `DECIMAL(p,s)`
- **字面量**:整数/小数/科学计数/字符串/日期(`DATE '2020-01-01'`)、`INTERVAL 30 days`
- **常用函数**:`count/sum/avg/min/max`、`coalesce`、`substring`、`concat`、`row_number/rank/dense_rank`、`size`(-> `array_length`)、`explode`(-> `unnest`)等
- 标识符支持反引号(`` `order count` ``)和大小写混合;DuckDB 保留字自动引号化

## 显式不支持的特性

遇到这些语法会明确报错(而不是静默出错结果):

| 特性 | 说明 |
|---|---|
| DDL(CREATE/ALTER/DROP) | Phase 2 |
| `NATURAL JOIN` | 需要同名列解析 |
| 命名窗口 `WINDOW w AS ...` | 先写 `OVER (...)` 内联形式 |
| 聚合 `FILTER (WHERE ...)` 子句 | |
| Lambda / TRANSFORM | |

## 常见问题排查

**`select 1` 都解析失败?**
确认使用的是当前版本。历史版本不支持小写关键字,现已在词法层做大小写折叠;若仍失败,检查是否端口连到了别的服务。

**客户端连不上?**
1. `lsof -iTCP:10000 -sTCP:LISTEN` 确认进程监听;若提示端口被占,说明有旧实例还在跑——先 `pkill -f runtime.jar` 再启动(当前版本端口被占会直接报错退出,不会静默假活);
2. 客户端 JDBC URL 必须是 `jdbc:hive2://` 前缀(不是 `jdbc:duckdb`);
3. 用了自定义端口时,URL 里的端口要一致。

服务端**同时支持 SASL PLAIN(标准 Hive JDBC 默认)和 NOSASL(`;auth=noSasl`)两种传输**——按连接首字节自动识别,无需特殊配置。查询卡住无响应时,先看服务端日志是否收到 `ExecuteStatement`;完全没日志通常是连到了旧实例或别的进程占了端口。

**IDEA 里看不到 database/schema 或表?**
DataGrip/IntelliJ 的内省依赖 `SHOW DATABASES` / `SHOW TABLES` / `DESCRIBE` 语句,当前版本已支持;若仍为空,确认连接的是**新启动**的进程(旧版本进程不支持这些语句)。服务端日志会打印收到的每条 SQL(`ExecuteStatement [session=.., db=..]: ...`),可直接对照排查客户端到底发了什么。另外确认 OpenSession 返回的协议版本正常(Hive 1.2 驱动对 `serverProtocolVersion` 为空会直接报 `Required field 'serverProtocolVersion' is unset`,当前版本会回显客户端请求的版本)。

**查询报 `Referenced table "xxx" not found`?**
表名拼写要和注册进 Catalog 的一致(当前内置 `users`/`orders`,在 `default` 库)。

**查询报 `UnsupportedOperationException: ...`?**
命中了上表的显式不支持特性,按报错提示改写 SQL。

**日期比较行为和 Spark 不一致?**
优先用显式 `CAST('2020-01-01' AS DATE)` 或 `DATE '2020-01-01'` 字面量,避免裸字符串与 DATE 列比较时的隐式转换差异。

## 运行测试

```bash
# 全量(284 个)
mvn clean test

# 只跑 TPC-DS 差分一致性(135 个,需要网络装 DuckDB tpcds 扩展)
mvn -pl localsql-thrift -am test -Dtest=TpcdsConsistencyTest

# 只跑翻译回归(136 个,不执行)
mvn -pl localsql-thrift -am test -Dtest=TpcdsQueryPipelineTest
```

测试体系分三层:

| 层 | 测试类 | 内容 |
|---|---|---|
| L1 翻译回归 | `TpcdsQueryPipelineTest` | 136 个 TPC-DS 查询 parse->generate,不执行 |
| L3 手工 golden | `TpcdsGoldenResultTest` | 走真实 Thrift 端点执行,与 Spark 语义手工校准值对比 |
| L3 差分 oracle | `TpcdsConsistencyTest` | 同一数据同一 SQL:期望侧 DuckDB 原生执行原文,实际侧走 Thrift 管线,双向 `EXCEPT ALL` 严格对比,135/135 |

更多面向开发者的架构约束见根目录 `AGENTS.md`。
