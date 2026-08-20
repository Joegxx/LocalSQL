# LocalSQL

**[中文文档](./README.zh-CN.md)** | English

> **A lightweight SQL Sandbox enabling developers and AI agents to complete 90% of SQL development, debugging, and verification locally — without touching production data platforms.**
> **让任何人，尤其是 AI，在不触碰生产数据平台的情况下完成 90% 的 SQL 开发、调试和验证。**

LocalSQL is an embedded SQL runtime that lets you write and test production SQL locally using Spark SQL 3.2.0 syntax, executed in-process via DuckDB. It exposes a standard HiveServer2 Thrift interface for JDBC clients and database IDEs, requiring no Spark, Hadoop, or Hive deployment.

**Core Philosophy:** Production is for execution. LocalSQL is for experimentation.

```text
Production Data Platform
        │
        │  Schema / Metadata / Samples
        ▼
┌───────────────────────────┐
│     LocalSQL Sandbox      │
│                           │
│  Production Metadata      │
│  Production Samples       │
│  Relationship-aware Data  │
│  Local SQL Execution      │
│  AI Test Cases            │
│  Assertions               │
│  Production Risk Analysis │
└──────────────┬────────────┘
               │
               │ Verified SQL
               ▼
       Production Execution
```

---

## Why LocalSQL?

Traditional SQL development means running queries directly against production clusters — every iteration, every debugging cycle, every AI-generated attempt. This creates:

- **Unnecessary compute costs** from repeated trial-and-error
- **Production risk** from untested queries hitting real data
- **Slow feedback loops** waiting for cluster scheduling
- **AI amplified problems** when agents iterate blindly against production

LocalSQL moves the entire debug cycle local:

```text
AI / Developer
      │
      ▼
 LocalSQL Sandbox
      │
      ├── Parse
      ├── Validate
      ├── Execute locally
      ├── Test with cases
      ├── Assert correctness
      └── Analyze production risk
      │
      ▼
 Production (only verified SQL)
```

---

## Features

### Current (MVP)

- ✅ **Spark SQL 3.2.0 syntax** — full ANTLR grammar support
- ✅ **DQL queries** — SELECT, JOIN, GROUP BY, HAVING, ORDER BY, LIMIT
- ✅ **Advanced SQL** — CTE, UNION, subqueries, window functions, ROLLUP/CUBE/GROUPING SETS
- ✅ **HiveServer2 Thrift** — connect with DBeaver, DataGrip, or any Hive JDBC client
- ✅ **Common IR** — clean abstraction layer between parsers and execution backends
- ✅ **Semantic analyzer** — name resolution, type inference, catalog lookup
- ✅ **Rewrite engine** — function translation (e.g., `size` → `array_length`)
- ✅ **DuckDB execution** — fast in-process SQL engine, single-file database
- ✅ **135/135 TPC-DS queries** — differential consistency testing against DuckDB native execution

### Roadmap

#### Phase 1 — Local SQL Sandbox
- ⬜ Sandbox database packaging
- ⬜ Production schema import
- ⬜ Sample data generation
- ⬜ Local SQL CLI

#### Phase 2 — Production Dataset Mirror
- ⬜ Production metadata extraction
- ⬜ Table sampling with relationship preservation
- ⬜ Data masking
- ⬜ Column statistics and cardinality
- ⬜ Partition metadata
- ⬜ Reproducible dataset versioning

#### Phase 3 — AI SQL Testing
- ⬜ Test case API
- ⬜ Synthetic test data generation
- ⬜ Edge-case generation
- ⬜ Assertions framework
- ⬜ Result comparison
- ⬜ SQL regression tests
- ⬜ AI agent interface

#### Phase 4 — Production Risk Analysis
- ⬜ Full-scan detection
- ⬜ Partition filter detection
- ⬜ Join explosion detection
- ⬜ Cardinality analysis
- ⬜ Production row-count awareness
- ⬜ Query complexity analysis
- ⬜ Configurable risk policies

#### Phase 5 — Production Submission Gateway
- ⬜ Verification report
- ⬜ Sandbox versioning
- ⬜ Query approval workflow
- ⬜ Production execution gateway
- ⬜ Execution limits
- ⬜ Audit records

---

## Quick Start

### Requirements

- JDK 21
- Maven 3.9+

```bash
java -version    # should show 21.x
mvn -version
```

### Build

```bash
mvn clean package -DskipTests
```

Produces: `localsql-app/target/runtime.jar`

### Run

```bash
# Start HiveServer2 Thrift on port 10000
java -jar localsql-app/target/runtime.jar

# Custom port
java -jar localsql-app/target/runtime.jar 10001
```

### Connect

**JDBC URL:**
```
jdbc:hive2://localhost:10000/default
```

Use with DBeaver, DataGrip, or any HiveServer2-compatible client. No authentication required in MVP.

**Example query:**

```sql
SELECT u.name, count(*) AS cnt
FROM users u
JOIN orders o ON u.id = o.user_id
GROUP BY u.name
ORDER BY cnt DESC;
```

**Expected result:**
```
alice  2
bob    1
```

---

## Architecture

LocalSQL maintains a lightweight, non-Calcite architecture focused on clean separation:

```text
Spark SQL
   ↓
ANTLR ParseTree
   ↓
Common IR (single source of truth)
   ↓
Semantic Analyzer
   ↓
Rewrite Engine
   ↓
DuckDB SQL
   ↓
DuckDB Executor
   ↓
Query Result
```

### Design Principles

1. **Module independence** — strict layer boundaries, no cross-layer leaks
2. **IR is the single source of truth** — backends consume IR, never ParseTree
3. **Parser only parses** — produces parse trees, no logic
4. **Analyzer only resolves** — name resolution, type inference, catalog queries
5. **Generator only serializes** — IR → SQL string, stateless, no catalog access
6. **Executor only executes** — runs SQL, returns results, doesn't touch IR
7. **Catalog only stores metadata** — databases/tables/columns, no SQL execution
8. **Metadata vs Runtime separation** — Catalog is logical, DuckDB is physical

### Modules

| Module | Responsibility |
|--------|----------------|
| `localsql-parser` | ANTLR grammar + Spark SQL ParseTree |
| `localsql-ir` | Common IR independent of parsers and backends |
| `localsql-spark` | Spark ParseTree → Common IR conversion |
| `localsql-analyzer` | Name resolution, type inference, catalog lookup |
| `localsql-rewrite` | IR rewrites and function-name translation |
| `localsql-catalog` | Logical databases, tables, columns, CatalogStore API |
| `localsql-duckdb` | DuckDB SQL generation, execution, CatalogStore implementation |
| `localsql-thrift` | HiveServer2 Thrift service and query orchestration |
| `localsql-app` | Application entry point, sample data, executable JAR |

---

## Testing

```bash
# All tests (284 total)
mvn clean test

# TPC-DS consistency (135 queries, requires network for DuckDB tpcds extension)
mvn -pl localsql-thrift -am test -Dtest=TpcdsConsistencyTest

# Translation regression (136 queries, no execution)
mvn -pl localsql-thrift -am test -Dtest=TpcdsQueryPipelineTest
```

### Test Architecture

| Layer | Test Class | Content |
|-------|------------|---------|
| L1 Translation | `TpcdsQueryPipelineTest` | 136 TPC-DS queries parse→generate, no execution |
| L3 Manual Golden | `TpcdsGoldenResultTest` | Execute via real Thrift, compare with hand-verified Spark semantics |
| L3 Differential Oracle | `TpcdsConsistencyTest` | Same data + same SQL: DuckDB native vs. Thrift pipeline, bidirectional `EXCEPT ALL` strict comparison, **135/135 passing** |

---

## Documentation

- **[Usage Guide](docs/usage.md)** — detailed setup, JDBC connection, SQL features, troubleshooting
- **[AGENTS.md](AGENTS.md)** — architecture rules, module boundaries, development guidelines

---

## Supported SQL Features

Validated through TPC-DS differential consistency testing (135/135 queries):

- **Basic queries**: `SELECT` / `FROM` / `WHERE` / `GROUP BY` / `HAVING` / `ORDER BY` / `LIMIT`
- **Joins**: `INNER` / `LEFT` / `RIGHT` / `FULL OUTER` / `CROSS` / `SEMI` / `ANTI`, plus `JOIN ... USING (col)`
- **Set operations**: `UNION [ALL]` / `INTERSECT` / `EXCEPT`
- **CTEs**: `WITH a AS (...), b AS (...) SELECT ...`
- **Subqueries**: scalar, `EXISTS` / `NOT EXISTS`, `IN (subquery)`, derived tables with aliases
- **Window functions**: `OVER (PARTITION BY ... ORDER BY ...)` with `ROWS|RANGE BETWEEN` frames
- **Grouping extensions**: `ROLLUP` / `CUBE` / `GROUPING SETS` (including legacy `WITH ROLLUP`)
- **Expressions**: `CASE WHEN`, `IN` lists, `BETWEEN`, `LIKE` / `RLIKE`, `IS [NOT] NULL`
- **Operators**: `+ - * / %`, integer division `DIV` (→ `//`), bitwise `& | ^`, string `||`, null-safe `<=>` (`IS NOT DISTINCT FROM`)
- **CAST**: including `DECIMAL(p,s)`
- **Literals**: integers, decimals, scientific notation, strings, dates (`DATE '2020-01-01'`), `INTERVAL 30 days`
- **Functions**: `count/sum/avg/min/max`, `coalesce`, `substring`, `concat`, `row_number/rank/dense_rank`, `size` (→ `array_length`), `explode` (→ `unnest`)
- **Identifiers**: backtick-quoted (`` `order count` ``), case-insensitive, DuckDB reserved words auto-quoted

### Explicitly Unsupported (Phase 2)

- DDL (`CREATE` / `ALTER` / `DROP`)
- `NATURAL JOIN`
- Named windows (`WINDOW w AS ...`)
- Aggregate `FILTER (WHERE ...)` clause
- Lambda expressions / `TRANSFORM`

---

## AI-Native SQL Workflow

LocalSQL is designed for AI agents to iterate safely:

```text
User Request
     │
     ▼
   AI Agent
     │
     ▼
Generate SQL
     │
     ▼
Inspect Schema
     │
     ▼
Run in LocalSQL
     │
     ▼
Generate Test Cases
     │
     ▼
Execute Test Cases
     │
     ▼
Assertions
     │
     ▼
Risk Analysis
     │
     ├──── FAIL ────► Fix SQL ────┐
     │                             │
     │                             └──► Run Again
     │
     ▼
   PASS
     │
     ▼
Production Submission
```

AI can iterate unlimited times locally without consuming production compute or risking production stability.

---

## Why DuckDB?

- **Single-file** — `company-sandbox.duckdb` is the entire database
- **In-process** — no server dependencies
- **Fast startup** — immediate query execution
- **Strong SQL** — comprehensive analytics capabilities
- **Distribution-friendly** — share sandbox datasets as files

---

## Non-Goals


LocalSQL does NOT aim to be:

- A distributed compute engine
- A production data warehouse
- A Spark / StarRocks / Trino replacement
- A general-purpose OLTP database
- A query optimizer or cost-based planner

LocalSQL's goal:

> **Provide a safe, lightweight, local experimentation environment between developers/AI and production SQL submission.**

---

## Current Limitations

- DQL only; DDL is Phase 2
- No optimizer, statistics, or cost model
- No transactions, ACID, or authorization
- No distributed execution
- Sandbox results ≠ production results (by design — sandbox tests logic, not scale)

---

## Contributing

Contributions welcome. Before submitting:

1. Read [AGENTS.md](AGENTS.md) for architecture rules
2. Run full test suite: `mvn clean test`
3. Ensure TPC-DS consistency tests pass: `mvn -pl localsql-thrift -am test -Dtest=TpcdsConsistencyTest`
4. Follow existing code style and module boundaries

---

## License

Apache License 2.0

---

## Philosophy

```text
Production is for execution.
LocalSQL is for experimentation.
```

Or put another way:

```text
Let AI make mistakes locally,
not in production.
```

The goal is to shift SQL development from:

```text
Write → Execute in Production → Observe → Fix → Repeat
```

To:

```text
Write → Execute Locally → Test → Assert → Analyze Risk → Verify → Production
```

Only verified SQL reaches production.

