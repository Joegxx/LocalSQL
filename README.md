# LocalSQL

**[中文文档](./README.zh-CN.md)** | English

LocalSQL is an embedded SQL sandbox for developers and AI agents. It parses Spark SQL 3.2.0 syntax, translates queries through Common IR, executes them in-process with DuckDB, and exposes a HiveServer2-compatible Thrift interface. No Spark, Hadoop or Hive service deployment is required.

Production is for execution. LocalSQL is for local experimentation.

## Current status

Updated September 10, 2026. Basic query execution, DML/DDL, file-backed databases and Java UDF integration paths exist. **Name resolution, failure recovery, packaged UDF execution and Thrift behavior have known correctness gaps. LocalSQL is not yet a Spark semantic-equivalence validator.**

The next milestone is correctness and module-boundary cleanup. See the [development plan](docs/development-plan.md) for scoped fixes, migration order and acceptance criteria. The plan describes future work, not completed fixes.

### Implemented paths

- Spark 3.2.0 grammar-based parsing, case-insensitive keywords and backtick identifiers. Parsing a construct does not imply its execution semantics are supported.
- DQL: SELECT, DISTINCT, JOIN, WHERE, GROUP BY, HAVING, ORDER BY and integer-literal LIMIT.
- CTEs, subqueries, UNION/INTERSECT/EXCEPT, window functions and ROLLUP/CUBE/GROUPING SETS.
- INSERT INTO/OVERWRITE; CREATE TABLE including CTAS/LIKE/PARTITIONED BY; CREATE/DROP VIEW; CREATE/ALTER/DROP DATABASE; table column changes, rename and properties.
- HiveServer2 connection, query, result-fetch and metadata paths, plus direct embedded execution through `ThriftServer.executeSparkSql`.
- In-memory Catalog with optional DuckDB-backed table metadata; physical file-database load/save paths.
- Local DDL-file import and function configuration. Java UDF/GenericUDF/GenericUDTF execution is in-process, not an isolated worker service.
- TPC-DS translation and DuckDB differential suites, function tests, DDL/DML and UDF regression examples.

### Known limitations

- Analyzer is integrated but does not complete scope-aware name/type binding. A nonexistent database qualifier can resolve to an existing physical table in another logical database.
- DDL rollback does not restore the in-memory Catalog. Empty databases and database-level properties are not persisted.
- **DDL import failure cleanup can delete an existing same-named physical table from another logical database. Use disposable database copies until fixed.**
- Non-literal LIMIT expressions and SORT BY can be silently ignored; unsupported-feature rejection is incomplete.
- Packaged `runtime.jar --udf-config ...` fails on missing Hive runtime classes. Registering an unused UDF can also incorrectly reject ordinary `SELECT *` queries.
- Thrift GetTypeInfo, result type metadata, operation states, cancellation and session cleanup require correction. Interface method coverage does not establish protocol compatibility.
- Function conversion is split between Rewrite and Generator; CatalogService still modifies runtime SQL. These are deviations from the intended boundaries below.
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
jdbc:hive2://localhost:10000/default
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

The normal query path is ParseTree → Common IR → Analyzer → Rewrite → DuckDB SQL → Executor. Analyzer and Rewrite are already integrated. `SqlPipeline` handles DDL/DML and optional UDF execution stages; SHOW/DESCRIBE/USE are handled before the regular pipeline.

### Intended boundaries

1. Modules have explicit dependency boundaries; backends never read Spark/ANTLR trees directly.
2. Parser produces ParseTree; the Spark AST builder produces IR.
3. Analyzer owns names, scopes, types, function binding and Catalog lookup.
4. Rewrite owns expression/function lowering; Generator only serializes resolved, lowered IR.
5. Executor executes SQL and returns results without inspecting IR.
6. Catalog stores logical metadata, not JDBC connections, physical SQL or runtime state.
7. Runtime orchestration coordinates transactions, physical bindings and UDF stages.

The Analyzer, Generator and CatalogService deviations described above are open work, not exceptions to adopt in new code. New dialects may require additional IR and semantic rules; they are not simply parser replacements.

### Modules

| Module | Current responsibility |
|---|---|
| `localsql-parser` | ANTLR grammar and Spark SQL ParseTree |
| `localsql-ir` | Common query, expression, statement and type representation |
| `localsql-spark` | Spark ParseTree → IR |
| `localsql-analyzer` | Table-output population and statement checks; complete binding pending |
| `localsql-rewrite` | Selected function renames and UDF/UDTF query splitting |
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
```

- `TpcdsQueryPipelineTest`: translation regression, including Analyzer/Rewrite calls; its Catalog fixture must be completed when binding becomes strict.
- `TpcdsGoldenResultTest`: real Thrift execution against manually calibrated expected results.
- `TpcdsConsistencyTest`: DuckDB native execution vs. the Thrift pipeline, stringified and compared as multisets. Oracle-unavailable cases check execution only. This does not validate result types, row ordering or Spark-specific semantics.

**Review baseline, September 10, 2026:** Java 21 packaging succeeded. After excluding seven socket-dependent classes, 455 tests passed. The full suite was blocked by sandbox port-binding restrictions and was not verified as passing. These counts are a dated snapshot, not a coverage or equivalence guarantee. Commands and exclusions are recorded in the development plan.

## Development direction

The immediate work is described in [docs/development-plan.md](docs/development-plan.md):

- Move semantic lowering from Generator to Rewrite; unify function binding/conversion.
- Separate CatalogService from runtime SQL and make metadata changes recoverable.
- Correct Analyzer binding, DDL rollback, UDF artifacts, unsupported syntax, Thrift behavior and database persistence.
- Add failure-injection, schema, ordered-result, restart and packaged-artifact tests.

Longer-term directions include sample-data import/generation, a local batch SQL regression CLI/API, reproducible datasets and additional dialects. Automatic AI test generation, production risk analysis, approval and production submission remain proposals, not delivered features. Multi-engine expansion and optimizer work are not the next milestone.

## Documentation and contributing

- [中文说明](README.zh-CN.md)
- [Usage guide](docs/usage.md)
- [Development plan](docs/development-plan.md) — Chinese; scope, proposals and acceptance criteria
- [Function notes](docs/functions.md)
- [UDF implementation/design notes](docs/udf-plan.md)
- [AGENTS.md](AGENTS.md) — architecture constraints and collaboration guidance

Before submitting a change, follow AGENTS.md, add regression tests and run the relevant suite with Java 21. Run the full/Thrift suites where the environment permits them; explicitly report unverified tests. Do not mark a planned capability as implemented until its code and acceptance tests are complete.

## License

Apache License 2.0
