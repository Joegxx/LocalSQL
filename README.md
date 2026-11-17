# LocalSQL

**[中文文档](./README.zh-CN.md)** | English

LocalSQL is an embedded SQL sandbox for developers and AI agents. It parses Spark SQL 3.2.0 syntax — or StarRocks 3.x DQL via a per-connection dialect — translates queries through Common IR, executes them in-process with DuckDB, and exposes a HiveServer2-compatible Thrift interface. No Spark, Hadoop or Hive service deployment is required.

Production is for execution. LocalSQL is for local experimentation.

## Current status

Updated November 12, 2026. Basic query execution, DML/DDL, file-backed databases, Java UDF integration paths and the StarRocks dialect frontend (M0–M4) exist. **Type propagation, packaged UDF execution, Thrift behavior and failure recovery have known correctness gaps. LocalSQL is not yet a Spark/StarRocks semantic-equivalence validator.**

The next milestone is correctness and module-boundary cleanup. See the [development plan](docs/development-plan.md) for scoped fixes, migration order and acceptance criteria. The plan describes future work, not completed fixes.

### Implemented paths

- Spark 3.2.0 grammar-based parsing, case-insensitive keywords and backtick identifiers. Parsing a construct does not imply its execution semantics are supported.
- StarRocks dialect frontend (M0–M4): selected per connection with `;sql_dialect=starrocks`, built on the verbatim StarRocks 3.5.21 grammar (DQL only), producing the same IR. Dialect-scoped strict function catalog and rewrite, admin statements (`USE`/`SHOW CATALOGS`/`SET CATALOG`), and `EXPLAIN` returning the DuckDB translation. See the [compatibility matrix](docs/starrocks-compat.md).
- DQL: SELECT, DISTINCT, JOIN, WHERE, GROUP BY, HAVING, ORDER BY and integer-literal LIMIT.
- CTEs, subqueries, UNION/INTERSECT/EXCEPT, window functions and ROLLUP/CUBE/GROUPING SETS.
- INSERT INTO/OVERWRITE; CREATE TABLE including CTAS/LIKE/PARTITIONED BY; CREATE/DROP VIEW; CREATE/ALTER/DROP DATABASE; table column changes, rename and properties.
- HiveServer2 connection, query, result-fetch and metadata paths, plus direct embedded execution through `ThriftServer.executeSparkSql`.
- In-memory Catalog with optional DuckDB-backed table metadata; physical file-database load/save paths.
- Local DDL-file import and function configuration. Java UDF/GenericUDF/GenericUDTF execution is in-process, not an isolated worker service.
- TPC-DS translation and DuckDB differential suites, function tests, DDL/DML and UDF regression examples.

### Known limitations

- Strict DQL name resolution has landed (layered scopes; wrong database/table/column and ambiguous references are rejected before execution; star expansion, alias visibility and lambda binding are handled), but type propagation is still incomplete and unknown types fall back to defaults.
- DDL failure now rolls back the in-memory Catalog; reload recovery for a commit that failed in an unknown state is still pending (F2 finishing work).
- **Packaged `runtime.jar --udf-config ...` fails on missing Hive runtime classes (F3 residual).**
- Thrift: GetResultSetMetadata still reports every column as STRING, GetOperationStatus always reports FINISHED, invalid sessions fall back to default and CloseOperation does not validate handles (F5 residual).
- The StarRocks frontend is DQL-only: model DDL (`PRIMARY KEY`/`DISTRIBUTED BY`), INSERT/UPDATE/DELETE and UDF paths are rejected; `||` is fixed to CONCAT semantics (sql_mode=34).
- DELETE/UPDATE/MERGE, multi-INSERT, NATURAL JOIN, named windows and aggregate FILTER are unsupported. Script TRANSFORM queries are unsupported; selected higher-order lambda functions are implemented, subject to the [function notes](docs/functions.md).
- No optimizer, cost model, distributed execution, authorization or user-facing transaction/ACID guarantees. Internal transactions exist for selected DDL/DML operations.

Supported paths and passing samples do not guarantee every SQL combination or Spark edge case. Local samples also do not establish production-scale behavior.

## Quick start

### Requirements and build

Use JDK 21 and Maven 3.9+. Check both commands: Maven can use a different JDK from `java`.

```bash
java -version
mvn -version
mvn clean package -DskipTests
```

The executable artifact is `localsql-app/target/runtime.jar`.

```bash
# Start the demo on port 10000; an optional positional argument changes the port
java -jar localsql-app/target/runtime.jar
java -jar localsql-app/target/runtime.jar 10001

# Open an existing file database; current name-resolution limitations still apply
java -jar localsql-app/target/runtime.jar --load sandbox.duckdb --schema default
```

The demo creates typed `users` and `orders` sample tables. The process remains running until stopped.

### Connect and query

Use a Hive JDBC data source in DBeaver/DataGrip or another compatible client:

```text
jdbc:hive2://localhost:10000/default                         # Spark SQL dialect (default)
jdbc:hive2://localhost:10000/default;sql_dialect=starrocks   # StarRocks dialect (DQL)
```

MVP authentication is not enforced. Metadata, type and lifecycle limitations are listed above; not every client feature is verified.

```sql
SELECT u.name, count(*) AS cnt
FROM users u
JOIN orders o ON u.id = o.user_id
GROUP BY u.name
ORDER BY cnt DESC;
```

The sample data returns `alice, 2` and `bob, 1`.

`Main` also accepts `--import-ddl`, `--out`, `--manifest`, `--failures`, `--udf-config` and `--function-map`. These are existing entry points, not guarantees that the import/UDF defects are fixed. See the [usage guide](docs/usage.md).

## Architecture

The normal query path is ParseTree → Common IR → Analyzer → Rewrite → DuckDB SQL → Executor. The Spark and StarRocks frontends both produce the same IR; Analyzer, Rewrite, Generator and Executor are dialect-agnostic. `SqlPipeline` handles DDL/DML and optional UDF execution stages; SHOW/DESCRIBE/USE are dispatched per dialect before the regular pipeline.

### Intended boundaries

1. Modules have explicit dependency boundaries; backends never read Spark/ANTLR trees directly.
2. Parser produces ParseTree; the Spark AST builder produces IR.
3. Analyzer owns names, scopes, types, function binding and Catalog lookup.
4. Rewrite owns expression/function lowering; Generator only serializes resolved, lowered IR.
5. Executor executes SQL and returns results without inspecting IR.
6. Catalog stores logical metadata, not JDBC connections, physical SQL or runtime state.
7. Runtime orchestration coordinates transactions, physical bindings and UDF stages.

Function conversion now lives exclusively in Rewrite and Generator only serializes IR (A1); CatalogService no longer patches runtime SQL (A2). The remaining deviation is incomplete Analyzer type propagation — an open item, not an exception to adopt in new code. The StarRocks frontend shows the intended pattern for new dialects: a grammar plus AST builders producing the same IR, with dialect-specific syntax explicitly rejected.

### Modules

| Module | Current responsibility |
|---|---|
| `localsql-parser` | ANTLR grammars (Spark 3.2.0 SqlBase, StarRocks 3.5.21) and parser entries |
| `localsql-ir` | Common query, expression, statement and type representation |
| `localsql-spark` | Spark ParseTree → IR |
| `localsql-starrocks` | StarRocks ParseTree → IR (DQL-only mirror builders; dialect normalization) |
| `localsql-analyzer` | Strict DQL name resolution and statement checks; type propagation pending |
| `localsql-rewrite` | Function renames, expression lowering and UDF/UDTF query splitting (sole owner of function conversion) |
| `localsql-catalog` | Logical metadata, cache and CatalogStore API; runtime separation pending |
| `localsql-duckdb` | SQL generation, JDBC execution, metadata store and built-in Java functions |
| `localsql-udf-api` | UDF definitions, registry and batch/gateway contracts |
| `localsql-udf-runtime` | In-process Hive UDF adapters; packaged runtime dependencies need repair |
| `localsql-thrift` | SQL orchestration and HiveServer2 RPC service |
| `localsql-app` | Entry point, sample data, DDL import, configuration and executable JAR |

## Testing

```bash
# Full suite: needs local port binding; TPC-DS execution also needs its extension
mvn clean test

# 135 SQL resources + one summary test, without executing the queries
mvn -pl localsql-thrift -am test \
  -Dtest=TpcdsQueryPipelineTest -Dsurefire.failIfNoSpecifiedTests=false

# DuckDB native vs. Thrift pipeline execution
mvn -pl localsql-thrift -am test \
  -Dtest=TpcdsConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false

# StarRocks frontend translation regression / dual-dialect twin differential
mvn -pl localsql-thrift -am test \
  -Dtest=StarRocksFrontendPipelineTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl localsql-thrift -am test \
  -Dtest=StarRocksTwinDifferentialTest -Dsurefire.failIfNoSpecifiedTests=false
```

- `TpcdsQueryPipelineTest`: translation regression, including Analyzer/Rewrite calls with a strict 24-table Catalog fixture.
- `TpcdsGoldenResultTest`: real Thrift execution against manually calibrated expected results.
- `TpcdsConsistencyTest`: DuckDB native execution vs. the Thrift pipeline, stringified and compared as multisets. Oracle-unavailable cases check execution only. This does not validate result types, row ordering or Spark-specific semantics.
- `StarRocksFrontendPipelineTest`: the official StarRocks TPC-DS benchmark corpus (103 statements) translated through the StarRocks frontend with strict function binding.
- `StarRocksTwinDifferentialTest`: the same SQL text executed by Spark and StarRocks sessions on identical `dsdgen` data; bidirectional `EXCEPT ALL` must be empty. Eight corpus queries using double-quoted aliases are rejected by the Spark grammar itself and only assert StarRocks-side execution.

**Review baseline, November 12, 2026:** Java 21 packaging succeeded. After excluding the three L3 port-dependent classes (`TpcdsConsistencyTest`/`TpcdsGoldenResultTest`/`StarRocksTwinDifferentialTest`), 733 tests passed; StarRocks L1 (104) and the twin differential (103) are green when run with a free port and the DuckDB tpcds extension. These counts are a dated snapshot, not a coverage or equivalence guarantee.

## Development direction

The immediate work is described in [docs/development-plan.md](docs/development-plan.md):

- Close the F3 residual (packaged UDF entry missing Hive runtime dependencies) and finish F2 (store reload recovery after a failed commit).
- Fix the remaining Thrift behavior gaps (result type metadata, operation status, session/handle validation).
- Continue resolved-IR validation gates and type propagation in the Analyzer.

Longer-term directions include sample-data import/generation, a local batch SQL regression CLI/API and reproducible datasets. Automatic AI test generation, production risk analysis, approval and production submission remain proposals, not delivered features. Multi-engine expansion and optimizer work are not the next milestone.

## Documentation and contributing

- [中文说明](README.zh-CN.md)
- [Usage guide](docs/usage.md)
- [StarRocks compatibility matrix](docs/starrocks-compat.md) — Chinese; three-state matrix with verification evidence
- [StarRocks dialect design](docs/starrocks-compat-plan.md) — Chinese
- [Development plan](docs/development-plan.md) — Chinese; scope, proposals and acceptance criteria
- [Function notes](docs/functions.md)
- [UDF implementation/design notes](docs/udf-plan.md)
- [AGENTS.md](AGENTS.md) — architecture constraints and collaboration guidance

Before submitting a change, follow AGENTS.md, add regression tests and run the relevant suite with Java 21. Run the full/Thrift suites where the environment permits them; explicitly report unverified tests. Do not mark a planned capability as implemented until its code and acceptance tests are complete.

## License

Apache License 2.0
