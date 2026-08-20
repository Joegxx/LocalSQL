# LocalSQL

中文 | **[English](./README.md)**

> **让任何人，尤其是 AI，在不触碰生产数据平台的情况下完成 90% 的 SQL 开发、调试和验证。**

LocalSQL 是一个轻量、嵌入式的 SQL Sandbox。它使用 Spark SQL 3.2.0 语法编写查询，通过 DuckDB 在进程内执行，并提供标准 HiveServer2 Thrift 接口供 JDBC 客户端和数据库 IDE 连接。无需部署 Spark、Hadoop 或 Hive。

**核心理念：生产环境用于执行，LocalSQL 用于实验。**

```text
生产数据平台
        │
        │  Schema / Metadata / Samples
        ▼
┌───────────────────────────┐
│     LocalSQL Sandbox      │
│                           │
│  生产环境元数据            │
│  生产环境样本              │
│  关系感知数据              │
│  本地 SQL 执行            │
│  AI 测试用例              │
│  断言                     │
│  生产风险分析              │
└──────────────┬────────────┘
               │
               │ 已验证的 SQL
               ▼
       生产环境执行
```

---

## 为什么需要 LocalSQL？

传统 SQL 开发意味着直接对生产集群运行查询——每次迭代、每次调试、每次 AI 生成的尝试都如此。这会导致：

- **不必要的计算成本** 来自反复试错
- **生产风险** 未经测试的查询直接访问真实数据
- **缓慢的反馈循环** 等待集群调度
- **AI 放大的问题** 当 AI Agent 盲目地对生产环境迭代时

LocalSQL 将整个调试循环迁移到本地：

```text
AI / 开发者
      │
      ▼
 LocalSQL Sandbox
      │
      ├── 解析
      ├── 验证
      ├── 本地执行
      ├── 测试用例
      ├── 断言正确性
      └── 分析生产风险
      │
      ▼
 生产环境（仅已验证的 SQL）
```

---

## 功能特性

### 当前版本 (MVP)

- ✅ **Spark SQL 3.2.0 语法** — 完整 ANTLR 语法支持
- ✅ **DQL 查询** — SELECT、JOIN、GROUP BY、HAVING、ORDER BY、LIMIT
- ✅ **高级 SQL** — CTE、UNION、子查询、窗口函数、ROLLUP/CUBE/GROUPING SETS
- ✅ **HiveServer2 Thrift** — 使用 DBeaver、DataGrip 或任何 Hive JDBC 客户端连接
- ✅ **Common IR** — 解析器与执行后端之间的清晰抽象层
- ✅ **语义分析器** — 名称解析、类型推导、Catalog 查询
- ✅ **重写引擎** — 函数翻译（例如 `size` → `array_length`）
- ✅ **DuckDB 执行** — 快速进程内 SQL 引擎，单文件数据库
- ✅ **135/135 TPC-DS 查询** — 与 DuckDB 原生执行的差分一致性测试

### 路线图

#### Phase 1 — 本地 SQL Sandbox
- ⬜ Sandbox 数据库打包
- ⬜ 生产环境 schema 导入
- ⬜ 样本数据生成
- ⬜ 本地 SQL CLI

#### Phase 2 — 生产数据集镜像
- ⬜ 生产环境元数据提取
- ⬜ 保持关系的表采样
- ⬜ 数据脱敏
- ⬜ 列统计信息和基数
- ⬜ 分区元数据
- ⬜ 可复现的数据集版本

#### Phase 3 — AI SQL 测试
- ⬜ 测试用例 API
- ⬜ 合成测试数据生成
- ⬜ 边界情况生成
- ⬜ 断言框架
- ⬜ 结果比对
- ⬜ SQL 回归测试
- ⬜ AI Agent 接口

#### Phase 4 — 生产风险分析
- ⬜ 全表扫描检测
- ⬜ 分区过滤检测
- ⬜ Join 爆炸检测
- ⬜ 基数分析
- ⬜ 生产环境行数感知
- ⬜ 查询复杂度分析
- ⬜ 可配置的风险策略

#### Phase 5 — 生产提交网关
- ⬜ 验证报告
- ⬜ Sandbox 版本管理
- ⬜ 查询审批工作流
- ⬜ 生产执行网关
- ⬜ 执行限制
- ⬜ 审计记录

#### Phase 6 — 多引擎支持
- ⬜ Spark SQL 4.x 语法支持
- ⬜ Doris SQL 方言
- ⬜ Snowflake SQL 方言
- ⬜ BigQuery SQL 方言
- ⬜ ClickHouse SQL 方言
- ⬜ Trino/Presto SQL 方言
- ⬜ 可插拔解析器架构
- ⬜ 方言特定的函数映射
- ⬜ 跨方言查询翻译

---

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9+

```bash
java -version    # 应显示 21.x
mvn -version
```

### 构建

```bash
mvn clean package -DskipTests
```

生成文件：`localsql-app/target/runtime.jar`

### 运行

```bash
# 在端口 10000 启动 HiveServer2 Thrift
java -jar localsql-app/target/runtime.jar

# 自定义端口
java -jar localsql-app/target/runtime.jar 10001
```

### 连接

**JDBC URL：**
```
jdbc:hive2://localhost:10000/default
```

使用 DBeaver、DataGrip 或任何兼容 HiveServer2 的客户端。MVP 版本无需认证。

**示例查询：**

```sql
SELECT u.name, count(*) AS cnt
FROM users u
JOIN orders o ON u.id = o.user_id
GROUP BY u.name
ORDER BY cnt DESC;
```

**预期结果：**
```
alice  2
bob    1
```

---

## 架构

LocalSQL 保持轻量架构，专注于清晰的层次分离：

```text
Spark SQL
   ↓
ANTLR ParseTree
   ↓
Common IR（唯一真相源）
   ↓
语义分析器
   ↓
重写引擎
   ↓
DuckDB SQL
   ↓
DuckDB 执行器
   ↓
查询结果
```

### 设计原则

1. **模块独立** — 严格的层边界，禁止跨层
2. **IR 是唯一真相源** — 后端消费 IR，绝不直接读 ParseTree
3. **Parser 只负责解析** — 产生 parse tree，不做任何逻辑
4. **Analyzer 只负责解析** — 名称解析、类型推导、Catalog 查询
5. **Generator 只负责序列化** — IR → SQL 字符串，无状态，不访问 Catalog
6. **Executor 只负责执行** — 运行 SQL，返回结果，不触碰 IR
7. **Catalog 只存储元数据** — databases/tables/columns，不执行 SQL
8. **元数据与运行时分离** — Catalog 是逻辑的，DuckDB 是物理的

**多引擎扩展性：** Common IR 设计使 LocalSQL 能够支持多种 SQL 方言（Spark 3.x/4.x、Doris、Snowflake、BigQuery、ClickHouse、Trino），只需添加新的解析器将其转换为相同的 IR。每种方言的解析器是独立的；分析器、重写器和 DuckDB 后端保持不变。

### 模块

| 模块 | 职责 |
|------|------|
| `localsql-parser` | ANTLR 语法 + Spark SQL ParseTree |
| `localsql-ir` | 独立于解析器和后端的 Common IR |
| `localsql-spark` | Spark ParseTree → Common IR 转换 |
| `localsql-analyzer` | 名称解析、类型推导、Catalog 查询 |
| `localsql-rewrite` | IR 重写和函数名翻译 |
| `localsql-catalog` | 逻辑数据库、表、列、CatalogStore API |
| `localsql-duckdb` | DuckDB SQL 生成、执行、CatalogStore 实现 |
| `localsql-thrift` | HiveServer2 Thrift 服务和查询编排 |
| `localsql-app` | 应用入口、示例数据、可执行 JAR |

---

## 测试

```bash
# 全部测试（284 个）
mvn clean test

# TPC-DS 一致性（135 个查询，需要网络下载 DuckDB tpcds 扩展）
mvn -pl localsql-thrift -am test -Dtest=TpcdsConsistencyTest

# 翻译回归（136 个查询，不执行）
mvn -pl localsql-thrift -am test -Dtest=TpcdsQueryPipelineTest
```

### 测试架构

| 层级 | 测试类 | 内容 |
|------|--------|------|
| L1 翻译 | `TpcdsQueryPipelineTest` | 136 个 TPC-DS 查询 parse→generate，不执行 |
| L3 手工 Golden | `TpcdsGoldenResultTest` | 通过真实 Thrift 执行，与手动验证的 Spark 语义对比 |
| L3 差分 Oracle | `TpcdsConsistencyTest` | 相同数据 + 相同 SQL：DuckDB 原生 vs. Thrift 管线，双向 `EXCEPT ALL` 严格对比，**135/135 通过** |

---

## 文档

- **[使用指南](docs/usage.md)** — 详细设置、JDBC 连接、SQL 特性、故障排查
- **[AGENTS.md](AGENTS.md)** — 架构规则、模块边界、开发指南

---

## 支持的 SQL 特性

通过 TPC-DS 差分一致性测试验证（135/135 个查询）：

- **基本查询**：`SELECT` / `FROM` / `WHERE` / `GROUP BY` / `HAVING` / `ORDER BY` / `LIMIT`
- **Join**：`INNER` / `LEFT` / `RIGHT` / `FULL OUTER` / `CROSS` / `SEMI` / `ANTI`，以及 `JOIN ... USING (col)`
- **集合操作**：`UNION [ALL]` / `INTERSECT` / `EXCEPT`
- **CTE**：`WITH a AS (...), b AS (...) SELECT ...`
- **子查询**：标量子查询、`EXISTS` / `NOT EXISTS`、`IN (子查询)`、派生表（含别名）
- **窗口函数**：`OVER (PARTITION BY ... ORDER BY ...)` 及 `ROWS|RANGE BETWEEN` frame
- **分组扩展**：`ROLLUP` / `CUBE` / `GROUPING SETS`（含 legacy `WITH ROLLUP`）
- **表达式**：`CASE WHEN`、`IN` 列表、`BETWEEN`、`LIKE` / `RLIKE`、`IS [NOT] NULL`
- **操作符**：`+ - * / %`、整数除法 `DIV`（→ `//`）、位运算 `& | ^`、字符串 `||`、空安全 `<=>`（`IS NOT DISTINCT FROM`）
- **CAST**：包括 `DECIMAL(p,s)`
- **字面量**：整数、小数、科学计数、字符串、日期（`DATE '2020-01-01'`）、`INTERVAL 30 days`
- **函数**：`count/sum/avg/min/max`、`coalesce`、`substring`、`concat`、`row_number/rank/dense_rank`、`size`（→ `array_length`）、`explode`（→ `unnest`）
- **标识符**：反引号引用（`` `order count` ``）、大小写不敏感、DuckDB 保留字自动引号化

### 显式不支持（Phase 2）

- DDL（`CREATE` / `ALTER` / `DROP`）
- `NATURAL JOIN`
- 命名窗口（`WINDOW w AS ...`）
- 聚合 `FILTER (WHERE ...)` 子句
- Lambda 表达式 / `TRANSFORM`

---

## AI 原生 SQL 工作流

LocalSQL 专为 AI Agent 安全迭代而设计：

```text
用户需求
     │
     ▼
   AI Agent
     │
     ▼
生成 SQL
     │
     ▼
检查 Schema
     │
     ▼
在 LocalSQL 中运行
     │
     ▼
生成测试用例
     │
     ▼
执行测试用例
     │
     ▼
断言
     │
     ▼
风险分析
     │
     ├──── 失败 ────► 修复 SQL ────┐
     │                             │
     │                             └──► 再次运行
     │
     ▼
   通过
     │
     ▼
生产提交
```

AI 可以在本地无限次迭代，而不消耗生产计算资源或危及生产稳定性。

---

## 为什么选择 DuckDB？

- **单文件** — `company-sandbox.duckdb` 就是整个数据库
- **进程内** — 无服务器依赖
- **快速启动** — 立即执行查询
- **强大的 SQL** — 全面的分析能力
- **易于分发** — 作为文件共享 sandbox 数据集

---

## 非目标

LocalSQL 不打算成为：

- 分布式计算引擎
- 生产数据仓库
- Spark / StarRocks / Trino 替代品
- 通用 OLTP 数据库
- 查询优化器或基于成本的计划器

LocalSQL 的目标：

> **在开发者/AI 与生产 SQL 提交之间提供安全、轻量、本地的实验环境。**

---

## 当前限制

- 仅支持 DQL；DDL 在 Phase 2
- 无优化器、统计信息或成本模型
- 无事务、ACID 或权限
- 无分布式执行
- Sandbox 结果 ≠ 生产结果（设计如此 — sandbox 测试逻辑，不测试规模）

---

## 贡献

欢迎贡献。提交前请：

1. 阅读 [AGENTS.md](AGENTS.md) 了解架构规则
2. 运行完整测试套件：`mvn clean test`
3. 确保 TPC-DS 一致性测试通过：`mvn -pl localsql-thrift -am test -Dtest=TpcdsConsistencyTest`
4. 遵循现有代码风格和模块边界

---

## 许可证

Apache License 2.0

---

## 哲学

```text
生产环境用于执行。
LocalSQL 用于实验。
```

或者换个说法：

```text
让 AI 在本地犯错，
而不是在生产环境犯错。
```

目标是将 SQL 开发从：

```text
编写 → 在生产环境执行 → 观察 → 修复 → 重复
```

转变为：

```text
编写 → 本地执行 → 测试 → 断言 → 风险分析 → 验证 → 生产
```

只有经过验证的 SQL 才进入生产环境。
