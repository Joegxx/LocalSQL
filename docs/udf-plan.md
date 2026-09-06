# UDF and Function Compatibility Plan

## Goal

LocalSQL will support four function sources without breaking its layer
boundaries:

1. Built-in Spark/Hive functions mapped to DuckDB expressions.
2. User Java Hive `UDF` classes.
3. Hive `GenericUDF` classes.
4. Hive `GenericUDTF` classes.

Multiple UDF jars must be loadable at the same time. User jars remain outside
the runtime jar and are loaded by an isolated local UDF Gateway.

## Boundaries

```text
Parser -> IR -> Analyzer -> Rewrite -> DuckDB Generator -> Executor
                         |                 |
                         |                 +-- built-in function mapping
                         +-- UDF binding    +-- UDF boundary
                                      |
                                      v
                               Local UDF Gateway
                                      |
                         Java UDF / GenericUDF / GenericUDTF
```

- `Catalog` stores function metadata only.
- `Analyzer` resolves function names, overloads, argument types and UDF
  bindings.
- `Rewrite` maps built-ins and inserts an explicit UDF boundary.
- `Generator` serializes IR and never loads or executes Java classes.
- `Executor` coordinates batches but does not depend on Hive ParseTree.
- The Gateway loads and executes Java UDFs in a separate process.

## Modules

```text
localsql-udf-api
  Stable UDF definitions, kinds, registry and wire-neutral batch contracts.

localsql-udf-runtime
  Hive ObjectInspector adapters and UDF lifecycle execution.

localsql-udf-gateway
  Separate JVM process, transport, worker management and jar isolation.
```

The first implementation may keep the Gateway transport in-process behind an
interface, but its public contracts must not expose class loaders or Hive
implementation objects. The production implementation will use a separate
JVM with one worker classloader per compatible Hive API version.

## Multiple jars and versions

Each UDF jar gets an isolated `URLClassLoader`. Registrations include the jar
path, checksum, class name, Hive API version, function kind, argument types,
return type and priority. Same-name functions are resolved by signature and
priority.

Jars compiled against incompatible Hive API versions use separate Gateway
worker JVMs. Same-version jars may share a worker while retaining independent
class loaders.

## Gateway protocol

The protocol is batch-oriented, not one RPC per row:

```text
UdfRequest  = requestId, udfId, kind, inputSchema, inputBatch, timeoutMs
UdfResponse = requestId, outputSchema, outputBatch, rowCount, errorInfo
```

Arrow `RecordBatch` or an equivalent length-prefixed binary representation is
preferred over JSON. The protocol must preserve NULLs, primitive values,
decimal, date, timestamp, array, map and struct values.

## UDF lifecycles

### Scalar UDF

First support simple Hive `UDF.evaluate` methods in projection and filter
expressions. The Gateway creates one instance per request worker and executes
input batches. Per-row Java calls are hidden inside the worker, not exposed
as network calls.

### GenericUDF

The worker performs:

```text
load -> instantiate -> initialize(ObjectInspector[]) -> evaluate batches
```

ObjectInspectors are adapted to LocalSQL types. Initialization happens once
per worker/function binding; NULL input, NULL output, type conversion and
function exceptions are explicit protocol outcomes.

### GenericUDTF

`GenericUDTF` is relational, not scalar. It binds to the existing `Generate`
IR relation:

```text
TableScan -> Generate(GenericUDTF) -> Project
```

The Gateway supports zero, one or many output rows for each input row,
including dynamic output schema, `LATERAL VIEW`, `OUTER`, `explode`,
`posexplode`, `inline`, `stack` and `json_tuple` style operations.

## Rollout phases

### Configuration (implemented)

`runtime.jar --load sandbox.duckdb --schema ods_fuaisv --udf-config udfs.yaml`:

```yaml
timeoutMillis: 30000          # per gateway call, optional
udfs:
  - name: mask_phone
    class: com.company.udf.MaskPhone
    jar: /opt/udf/company.jar   # jar file OR exploded class directory
    kind: GENERIC               # GENERIC (default) | TABLE | SCALAR
    args: [STRING, "DECIMAL(10,2)"]   # quote values containing YAML commas
    returns: STRING
    priority: 0
  - name: split_tags
    class: com.company.udf.SplitTags
    jar: /opt/udf/company.jar
    kind: TABLE
    args: [STRING]
```

`UdfConfigLoader` validates jar existence, kinds and types (primitives plus
DECIMAL(p,s); complex types rejected with a clear error). Ids default to
`name/arity`. The composite `HiveUdfGateway` dispatches GENERIC calls to the
projection splitter path and TABLE calls to the LATERAL VIEW path.

### Phase 1: contracts and registry

- Add `localsql-udf-api`.
- Define `UdfDefinition`, `UdfKind`, `FunctionSignature`, registry and
  wire-neutral batch contracts.
- Add configuration parsing and validation without executing jars.

### Phase 2: Gateway and GenericUDF

- Add isolated jar/classloader management.
- Add Gateway process and version handshake.
- Add Hive ObjectInspector adapters.
- Execute GenericUDF in batches.
- Initially allow projection, `WHERE` and `CASE` expressions.

### Phase 3: GenericUDTF

- Bind GenericUDTF to `Generate`.
- Support `LATERAL VIEW`, zero/multiple output rows and OUTER semantics.
- Add schema and row-expansion tests.

### Phase 4: Spark 3.5.9 built-ins

Maintain a structured compatibility registry with statuses:

```text
DIRECT, MAPPED, REWRITTEN, GATEWAY, PARTIAL, UNSUPPORTED
```

Implement in this order:

1. String, numeric and null-handling functions.
2. Date and timestamp functions.
3. Array, map and struct functions.
4. Aggregate and statistical functions.
5. Window functions.
6. JSON, encoding and hash functions.
7. Higher-order functions and Lambda IR.
8. Restricted reflection and Java invocation functions.

The Spark 3.5.9 SQL function reference is the compatibility inventory. Each
function gets a translation, semantic notes, Spark example and regression
case. Functions introduced after the parser version are still subject to the
local grammar and IR support.

## Safety and failure behavior

- UDF jars are never copied into or shaded into `runtime.jar`.
- Reflection and `java_method` remain disabled unless explicitly allowlisted.
- Gateway requests have batch limits and timeouts.
- UDF failures identify request, function, class and jar without killing the
  main SQL runtime.
- Unsupported placement or type conversion fails explicitly; it must not
  silently become a DuckDB function with different semantics.

## Test strategy

- Registry: overloads, priorities, duplicate names and configuration errors.
- Isolation: multiple jars, classloader separation and Hive API versions.
- GenericUDF: initialize, NULLs, types, exceptions, timeout and batches.
- GenericUDTF: zero/multiple rows, dynamic schema, LATERAL VIEW and OUTER.
- Built-ins: Spark 3.5.9 examples compared with DuckDB/LocalSQL results.
- End-to-end: SQL -> IR -> binding -> Gateway -> DuckDB result.
