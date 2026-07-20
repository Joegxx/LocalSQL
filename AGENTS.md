# AGENTS.md

Guidance for AI agents working in this repo. Verified against the current codebase.

## What this is

LocalSQL: an embedded Spark SQL runtime. Pipeline: `Spark SQL → ANTLR parse → Common IR → rewrite → DuckDB SQL → DuckDB execute`. Goal is to run warehouse SQL locally without Spark/Hadoop/Hive. MVP scope is DQL only.

Design doc is a Feishu wiki (title "SQL Runtime"); the repo README is just a one-liner. Trust the code over any prose.

## Build & run

- **Java 21 required** (`maven.compiler.release=21`). The IntelliJ `.idea/misc.xml` may show JDK 24 — ignore it, the Maven config is the source of truth.
- Maven multi-module, parent pom at root. Full build: `mvn clean package -DskipTests`
- Run the demo (boots runtime, runs a sample JOIN/GROUP BY query against in-memory DuckDB tables):
  ```
  java -jar localsql-app/target/runtime.jar
  ```
  Exits after printing results (Main joins on shutdown hook). Pass a port arg to change the (placeholder) Thrift port.

## Module boundaries

Dependency flow (left depends on right):

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

- `localsql-parser` — ANTLR4 grammar + `SparkSqlParser` entry. **No logic here, only parse-tree.**
- `localsql-ir` — Common IR. `Relation` (relation/) and `Expression` (expression/) are sealed-ish hierarchies; `IrVisitor` does open recursion. **Must not depend on Spark or ANTLR.**
- `localsql-spark` — `SparkAstBuilder` converts ParseTree → IR. `SparkExpressionBuilder` and `SparkDataTypeBuilder` are package-private helpers.
- `localsql-duckdb` — `DuckDbSqlGenerator` (IR → SQL string) + `DuckDbExecutor` (JDBC over in-process DuckDB).
- `localsql-thrift` — `ThriftServer` orchestrates the full pipeline and serves a real HiveServer2 `TCLIService` Thrift RPC on port 10000 (via `TThreadPoolServer`). `LocalSqlThriftService` implements all 23 `TCLIService.Iface` methods; smoke tests in `src/test` cover OpenSession→ExecuteStatement→FetchResults and GetTables/GetColumns metadata.
- `localsql-app` — `Main` with sample data; shaded into `runtime.jar`.

## ANTLR codegen — critical quirks

- Grammar: `localsql-parser/src/main/antlr4/org/apache/spark/sql/catalyst/parser/SqlBase.g4`. This is **Spark 3.2.0's SqlBase.g4 verifiably copied** from the `v3.2.0` tag of apache/spark.
- **The grammar was modified in one place**: `fragment LETTER` changed from `[A-Z]` to `[a-zA-Z]`. The original only matches uppercase because Spark's runtime lexer does case-folding in Java code we don't have. Without this fix, lowercase identifiers tokenize as `UNRECOGNIZED` and every real query fails at `SELECT <lowercase>`. Do not "restore" the grammar to upstream.
- Generated parser/lexer classes land in `localsql-parser/target/generated-sources/antlr4/...` under package `org.apache.spark.sql.catalyst.parser`. This package collides with real Spark on purpose (so the g4 compiles unchanged); do not rename.
- ANTLR plugin runs automatically on `compile`. To regenerate only: `mvn -pl localsql-parser antlr4:antlr4`.
- `SqlBase.g4` uses parser member fields (`legacy_setops_precedence_enabled`, `SQL_standard_keyword_behavior`, etc.) with default `false` — leave them; MVP doesn't flip them.

## Pipeline execution order

When executing a query (see `ThriftServer.executeSparkSql`):
1. `SparkSqlParser.parseStatement` → `SingleStatementContext`
2. `SparkAstBuilder.buildStatement` → IR `Relation`
3. (Analyzer/RewriteEngine exist but are currently no-ops in the wired path — `ThriftServer` does not call them. If you add them, wire between parse and generate.)
4. `DuckDbSqlGenerator.generate` → DuckDB SQL string
5. `DuckDbExecutor.execute` → `QueryResult`

## SQL generation conventions (don't break these)

In `DuckDbSqlGenerator`, relations split into two kinds:
- **Query nodes** (`Project`/`Filter`/`Aggregate`/`Sort`/`Limit`/`Union`/`With`/`Generate`) — emit a full `SELECT ...`. When used as a `FROM` source they MUST be wrapped in `(...)` via `emitChildSource`.
- **Source nodes** (`TableScan`/`Join`/`Values`) — emit a table expression, no `SELECT *` prefix. `Join` emits `left JOIN right ON ...` directly.

`emitJoin` does NOT prefix `SELECT * FROM` — that was a bug. `FROM` clauses always go through `emitChildSource`, never raw `emit`.

Spark function → DuckDB mapping lives in `FN_MAP` (e.g. `size`→`array_length`, `explode`→`unnest`). `RewriteEngine` also has rename logic but `FN_MAP` is the active path.

## Known MVP gaps

- DDL not implemented (CREATE/ALTER/DROP are Phase 2).
- `RewriteEngine` and `SemanticAnalyzer` exist but aren't invoked in the run path.
- `ThriftServer` is a stub — no real HiveServer2 Thrift RPC. `jdbc:hive2://localhost:10000` won't actually connect yet.
- Table aliases only applied to `TableScan`; subquery aliases (`AliasedQuery`) are dropped.
- `Aggregate.aggregateExpressions` is overloaded to hold the whole select list (group cols + aggregates together) — not a clean Spark-style split.
- ROLLUP/CUBE/GROUPING SETS throw `UnsupportedOperationException`.

## Conventions

- No code comments unless requested.
- IR node classes: one public top-level class per file (Java requires it for records/sealed members — `DataType.java` only holds the sealed interface, concrete types are separate files under `ir/type/`).
- `FunctionCall.name` is mutable (via `rename()`) specifically so the rewrite layer can mutate it in place.
- Sample data in `Main` uses VARCHAR for all columns — DuckDB will coerce.
