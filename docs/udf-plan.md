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
  - name: star
    class: com.company.udf.StarUdf   # extends org.apache.hadoop.hive.ql.exec.UDF
    jar: /opt/udf/company.jar
    kind: SCALAR
    args: [STRING]
    returns: STRING
```

`UdfConfigLoader` validates jar existence, kinds and types (primitives plus
DECIMAL(p,s); complex types rejected with a clear error). Ids default to
`name/arity`. The composite `HiveUdfGateway` dispatches by kind: GENERIC and
SCALAR calls go through the projection split path (GenericUDF lifecycle vs
reflected `evaluate` with java-boxed > hadoop-writable > Object overload
matching; NULL args yield a NULL row like Hive), TABLE calls go through the
LATERAL VIEW path.

### Function mappings (implemented)

Built-in Spark-to-DuckDB mappings can be extended or overridden without code
changes:

```bash
java -jar runtime.jar --load sandbox.duckdb --udf-config udfs.yaml \
     --function-map functions.yaml
```

```yaml
functions:
  # rename: my_fn(a, b) -> target(a, b)
  my_size: array_length
  # template: {N} is replaced by the Nth argument, in any order/with literals
  next_friday: "date_add({0}, 7)"
  normalize_phone: "regexp_replace({0}, '[^0-9]', '', 'g')"
```

`FunctionMapLoader` (app) classifies values containing `{N}` as templates,
everything else as renames; custom entries override built-ins. The generator
is constructed with the overlay via `ThriftServer(catalog, executor, db,
udfSupport, generator)`.

### Higher-order functions (implemented, Lambda IR)

- `Lambda` IR node + parser support (`x -> e`, `(x, y) -> e`) + DuckDB lambda
  emission.
- transform -> list_transform, filter -> list_filter (direct)
- exists -> (len(list_filter) > 0), forall -> (len(list_filter) = len) —
  PARTIAL: Spark null/empty nuances differ slightly
- aggregate(arr, zero, merge) -> list_reduce(list_concat(list_value(zero),
  arr), (acc, x) -> merge). A finish lambda is rejected explicitly (DuckDB
  cannot invoke lambdas)
- map lambdas: transform_values/transform_keys/map_filter lower the Spark
  two-variable lambda onto DuckDB single-variable lambdas over
  map_entries structs via an IR-level Identifier substituter (k/v become
  "e"."key"/"e"."value"): transform_values/keys ->
  map_from_entries(list_transform(map_entries(m), e -> {'key':..., 'value':...})),
  map_filter -> map_from_entries(list_filter(map_entries(m), e -> ...))
- date_format: Java SimpleDateFormat -> strftime translation for the common
  specifiers (yyyy/yy, MM/M/MMM, dd/d, HH, mm, ss, EEE/EEEE, a, D, w, u)
  with quoted-literal passthrough and %% escaping; unsupported tokens fail
  loudly; requires a literal format string. SSS (millis) is emitted as a
  concat of strftime pieces plus a microsecond-derived lpad millis segment
- struct/json: named_struct -> struct_pack, get_field -> struct_extract,
  from_json(json, '<spark schema literal>') -> from_json(json, '{...}' JSON
  schema) with a recursive schema parser supporting nested STRUCT/ARRAY/
  ARRAY-of-STRUCT/DECIMAL(p,s) and field COMMENT clauses (MAP still
  rejected); to_json direct
- percentile/percentile_approx/approx_percentile -> quantile_cont
- zip_with, sort_array (comparator), map_zip_with remain unsupported
  (need lambda variable substitution into DuckDB list_zip struct fields)

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

#### Batch 1 (implemented, verified by SparkFunctionMappingTest, 106 cases)

- **Strings**: upper/lower/reverse/ascii/chr/lpad/rpad/trim(+charset)/ltrim/
  rtrim/btrim/levenshtein/contains/startswith/endswith/concat_ws/repeat/
  replace/instr(->strpos)/locate+position(arg-swap+3-arg)/substring/length,
  regexp_replace(global 'g' flag)/regexp_extract/regexp_extract_all/
  regexp_count(len of extract_all)/regexp_like(->regexp_matches),
  split(->regexp_split_to_array; Spark uses regex)
- **Numbers**: abs/ceil/ceiling/floor/sqrt/cbrt/exp/ln/log2/log10/log(base,x)
  ratio/pow/power/sign/isnan/isinf/pi/mod(+NULL-on-zero guard)/pmod
- **Null/conditional**: nvl/ifnull(->coalesce)/nvl2(CASE)/if(CASE)/nullif/
  equal_null(IS NOT DISTINCT FROM)/greatest/least/coalesce
- **Dates**: year/month/day/dayofmonth/quarter/hour/minute/second/dayofweek
  (formula, Spark Sunday=1)/weekday(-1, Spark Monday=0)/dayofyear/
  weekofyear(->week)/last_day/date_add/date_sub(d +/- n)/datediff(arg-swap)/
  add_months(to_months + end-of-month clamp, CAST AS DATE)/to_date(1-2 arg)/
  to_timestamp(1-arg)/unix_timestamp(1-arg, 0-arg)/from_unixtime(strftime)
- **Hash/encoding/JSON**: md5/sha1/sha2(0|256)/hex/unhex/base64/unbase64/
  get_json_object(->json_extract_string)/json_array_length
- **Arrays**: array/array_contains/array_position/array_distinct/
  array_union/array_intersect/array_except/array_join(2-3 arg)/array_max/
  array_min/element_at/flatten/cardinality/size
- **Aggregates/windows**: any_value/first/median/mode/corr/covar_pop/
  covar_samp/stddev family/variance family/approx_count_distinct/
  collect_list(->list)/bool_and/bool_or/every/some/any, and the full
  window set (row_number..nth_value) plus max_by/min_by(->arg_max/arg_min)
- **PARTIAL, not auto-applied**: date_format(Java vs strftime pattern
  languages differ; the old strftime mapping was removed — it now fails
  loudly instead of producing wrong output), percentile/percentile_approx
  (->quantile semantics differ). date_trunc moved to batch 2 with literal
  unit translation.
- **List results are order-insensitive** for set functions: Spark does not
  guarantee element order for array_distinct/union/intersect/except, and
  DuckDB's list_* may differ from Spark's order.

#### Batch 2 (implemented, verified by SparkFunctionMappingTest — 179 total cases)

- **DuckDB-native names, now mapped directly**: trig/hyperbolic family
  (acos..atanh, cos/sin/tan + h variants, degrees/radians), lcase/ucase,
  char_length/character_length/octet_length(BLOB cast), left/right,
  translate, round, factorial, bin, printf, encode/decode (utf-8 only),
  now/random, date_part, make_date, map_concat/map_entries/
  map_from_entries/map_from_arrays(->map(list,list)), to_json, typeof,
  uuid, char(->chr), sha(->sha1), mean(->avg), std(->stddev_samp)
- **Restructured**: map(k1,v1,...) variadic (->map(list_value, list_value)),
  string/int/bigint/... cast functions, isnull/isnotnull, positive/negative,
  e(->exp(1)), expm1, log1p, hypot, nanvl, elt, space, find_in_set,
  substring_index(literal count, plain delimiter split), format_number
  (literal scale, printf %,.Nf), slice(->list_slice end arithmetic),
  array_remove(->list_filter IS DISTINCT FROM), current_catalog,
  current_timezone(->current_setting), date_from_unix_date/unix_date/
  unix_seconds/unix_millis/unix_micros, to_unix_timestamp(1-arg),
  count_if(->count(CASE)), collect_set(->list(DISTINCT)), try_divide
  (->NULLIF), months_between (Spark's year/month/day+time fraction
  formula), next_day (literal day names), date_trunc (literal unit
  translation: YEAR/YYYY/YY, MON/MM/MONTH, DD/DAY, week, quarter, hour,
  minute, second)
- **Known not-yet-supported (fail explicitly)**: date_format (Java vs
  strftime pattern languages differ — fails loudly instead of silently
  mis-formatting), initcap, bround/rint, conv, getbit/bit_get,
  bit_and/bit_or/bit_xor (aggregates), kurtosis/skewness, crc32/hash/
  xxhash64 (different algorithms), percentile family (quantile semantics),
  sequence, sort_array, array_repeat, arrays_zip, arrays_overlap (NULL
  nuance differs), json_object_keys, to_csv/from_csv/schema_of_csv,
  months_between second-arg precision beyond the documented formula,
  inline/inline_outer/explode_outer/posexplode_outer (OUTER semantics),
  higher-order lambdas (aggregate/filter/exists/forall/transform/
  transform_keys/transform_values/zip_with/map_filter/map_zip_with —
  require the Lambda IR), window/session_window, grouping/grouping_id,
  extract (grammar), overlay, sentences, parse_url, xpath family,
  to_timestamp_ltz/ntz, make_interval family, session_window,
  input_file_name/spark_partition_id/monotonically_increasing_id,
  raise_error/assert_true, current_user, java_method/reflect (disabled
  by design), version (DuckDB version string would mislead)

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
