# 函数支持一览

按 Spark SQL 3.5.x 名称列出已实现的函数,括号内是翻译到的 DuckDB 目标。生成器函数映射(`DuckDbSqlGenerator.FN_MAP` + 参数重构 `emitMappedFunction` + 连接级 Java UDF)由 `SparkFunctionMappingTest` 参数化回归保护(232 个表达式用例);有语义差异或 DuckDB 限制的条目单独标注。

- [字符串](#字符串)
- [数值](#数值)
- [日期时间](#日期时间)
- [数组](#数组)
- [MAP](#map)
- [STRUCT / JSON](#struct--json)
- [聚合](#聚合)
- [窗口](#窗口)
- [高阶函数(Lambda)](#高阶函数lambda)
- [条件 / 类型 / 其他](#条件--类型--其他)
- [Java UDF 外挂(DuckDB 缺失的内置函数)](#java-udf-外挂duckdb-缺失的内置函数)
- [已知限制清单](#已知限制清单)

## 字符串

| Spark 函数 | DuckDB 目标 | 备注 |
|---|---|---|
| `length` / `char_length` / `character_length` | 同名 | |
| `substr` / `substring` | `substring` | |
| `concat` / `concat_ws` | 同名 | |
| `upper` / `lower` / `ucase` / `lcase` | 同名 | |
| `reverse` / `ascii` / `chr` / `char` | 同名 / `chr` | |
| `lpad` / `rpad` / `trim` / `ltrim` / `rtrim` / `btrim` | 同名 | |
| `levenshtein` / `soundex` | 同名 | |
| `contains` / `startswith` / `endswith` | `contains` / `starts_with` / `ends_with` | |
| `repeat` / `replace` / `translate` / `printf` | 同名 | |
| `instr` | `strpos` | |
| `left` / `right` | 同名 | |
| `split` | `regexp_split_to_array` | Spark 正则转 DuckDB 正则 |
| `locate` | 参数重构 | `locate(sub, str[, pos])` → `position(sub IN str)`(3 参从 pos 起) |
| `regexp_like` | `regexp_matches` | |
| `regexp_extract` / `regexp_extract_all` | 同名 | |
| `regexp_replace` | 同名 | 自动加全局 `'g'` flag(Spark 语义) |
| `regexp_count` | `regexp_count` | |
| `find_in_set` | 参数重构 | `1 + list_position(...)` |
| `substring_index` | 参数重构 | 字面量 count,正/负向展开 |
| `elt` | 参数重构 | 1-based 取第 N 个参数 |
| `space` | 参数重构 | `repeat(' ', n)` |
| `format_number` | 参数重构 | 字面量 scale → `printf`/四舍五入 |
| `hex` / `unhex` | 同名 | |
| `base64` / `unbase64` | `to_base64` / `from_base64` | |
| `encode` / `decode` | 同名 | 仅 UTF-8(Spark 默认) |
| `octet_length` | 参数重构 | BLOB cast 后取字节长度 |
| `initcap` | Java UDF | 词首字母大写、其余小写(字母数字词边界) |
| `conv` | Java UDF | 完整 Spark 语义(见 Java UDF 外挂节) |

## 数值

| Spark 函数 | DuckDB 目标 | 备注 |
|---|---|---|
| `abs` / `sqrt` / `cbrt` / `exp` / `ln` / `log2` / `log10` | 同名 | |
| `log(base, x)` | 参数重构 | `ln(x)/ln(base)`;`log(x)` → `ln(x)` |
| `pow` / `power` | `pow` | |
| `ceil` / `ceiling` / `floor` / `round` | 同名 | |
| `sign` / `signum` | `sign` | |
| `factorial` / `bin` | 同名 | |
| `cos` / `acos` / `cosh` / `acosh` / `sin` / `asin` / `sinh` / `asinh` / `tan` / `atan` / `atan2` / `atanh` / `cot` | 同名 | |
| `degrees` / `radians` | 同名 | |
| `md5` / `sha1` / `sha` / `sha2` | 同名 / `sha1` / 参数重构 | `sha2(x, 0|256)` → `sha256` |
| `mod` / `pmod` | 参数重构 | `(a % b + b) % b` 归一化(Spark pmod 语义) |
| `e` / `pi` | `e()` / `pi()` | |
| `expm1` / `log1p` / `hypot` / `nanvl` | `exp(x)-1` / `ln(1+x)` / `pow(x,2)+pow(y,2)` 开方 / `CASE` | DuckDB 无对应 |
| `positive` / `negative` | `+x` / `-x` | |
| `isnan` / `isinf` | 同名 | |
| `try_divide` | 参数重构 | `a / NULLIF(b, 0)` |
| `bround` | Java UDF | HALF_EVEN(BigDecimal),1 参/2 参重载 |

## 日期时间

| Spark 函数 | DuckDB 目标 | 备注 |
|---|---|---|
| `current_date` / `current_timestamp` / `now` | 同名 | |
| `year` / `month` / `day` / `dayofmonth` / `quarter` / `hour` / `minute` / `second` / `dayofyear` / `weekofyear` | 同名 / `week` | |
| `date_part` / `make_date` | 同名 | |
| `last_day` | 同名 | |
| `date` / `timestamp` / `string` / `binary` | CAST 函数族 | |
| `date_add` / `date_sub` | 参数重构 | `date + INTERVAL n DAY` |
| `datediff` | 参数重构 | `date_diff('day', b, a)`(Spark 参数序 a-b) |
| `add_months` | 参数重构 | `+INTERVAL i MONTH` + 月末钳制 |
| `months_between` | 参数重构 | 年月日 + 时间分数公式 |
| `dayofweek` | 参数重构 | DuckDB 公式换算(Spark 周日=1) |
| `weekday` | 参数重构 | `dayofweek-2`(Spark 周一=0) |
| `next_day` | 参数重构 | 字面量日名(`next Monday` 语法) |
| `date_trunc` | `date_trunc` | 字面量单位翻译(quarter→quarter 等) |
| `date_format` | 参数重构 | Java pattern → strftime 翻译(含 `SSS` 毫秒段) |
| `to_date` / `to_timestamp` | 参数重构 | 格式串 → `strptime`;无格式 → CAST |
| `unix_timestamp` | 参数重构 | 无格式 `epoch(TIMESTAMP)`;带格式 `strptime` |
| `to_unix_timestamp` | 参数重构 | `epoch(CAST(... AS TIMESTAMP))` |
| `from_unixtime` | 参数重构 | `strftime`;默认格式 `yyyy-MM-dd HH:mm:ss` |
| `unix_seconds` / `unix_millis` / `unix_micros` | `epoch` / `epoch_ms` / `epoch_us` | |
| `unix_date` | 参数重构 | `epoch(DATE)` |
| `date_from_unix_date` | 参数重构 | `DATE 'epoch' + days` |
| `current_timezone` | `strftime(now(), '%Z')` | |

## 数组

| Spark 函数 | DuckDB 目标 | 备注 |
|---|---|---|
| `array` | `list_value` | |
| `size` / `cardinality` | `array_length` / `len` | |
| `array_contains` | `list_contains` | |
| `array_position` | `list_position` | |
| `array_distinct` / `array_intersect` / `array_except` / `array_union` | `list_distinct` / `list_intersect` / 参数重构 / 参数重构 | except/union 用 `list_distinct(list_concat(...))` 组合 |
| `array_join` | `array_to_string` | |
| `array_max` / `array_min` | `list_max` / `list_min` | |
| `array_remove` | `list_remove` | |
| `element_at` | `list_element` | |
| `flatten` / `sort_array` / `sequence` / `slice` | 同名 / 参数重构 / 同名 / 参数重构 | |
| `explode` / `posexplode` | `unnest` | |

## MAP

| Spark 函数 | DuckDB 目标 | 备注 |
|---|---|---|
| `map(...)` | 参数重构 | 变参 k,v 对 → `map(keys, values)` |
| `map_keys` / `map_values` / `map_entries` / `map_concat` | 同名 | |
| `map_from_arrays` | `map` | |
| `map_from_entries` | 同名 | |
| `element_at` | `list_element` | map 取值同理 |

## STRUCT / JSON

| Spark 函数 | DuckDB 目标 | 备注 |
|---|---|---|
| `named_struct` | `struct_pack` | |
| `get_field` | `struct_extract` | 支持嵌套链 |
| `to_json` | `to_json` | `to_json(NULL)` → NULL 原生一致 |
| `from_json(json, schema)` | 参数重构 | Spark schema 字面量 → DuckDB JSON schema(递归解析:嵌套 STRUCT / ARRAY / ARRAY-of-STRUCT / DECIMAL(p,s) / 字段 COMMENT);畸形 JSON → NULL(`json_valid` 守卫,Spark 语义);**MAP schema 不支持**(DuckDB JSON schema 无 map 形式) |
| `get_json_object` | `json_extract_string` | |
| `json_extract` / `json_array_length` / `json_object_keys` | 同名 / `json_keys` | |

## 聚合

| Spark 函数 | DuckDB 目标 | 备注 |
|---|---|---|
| `count` / `sum` / `avg` / `mean` / `min` / `max` / `first` | 同名 / `avg` | |
| `nvl` / `ifnull` | `coalesce` | |
| `any_value` | 同名 | |
| `median` / `mode` | 同名 | |
| `corr` / `covar_pop` / `covar_samp` | 同名 | |
| `std` / `stddev` / `stddev_pop` / `stddev_samp` | `stddev_samp` 族 | |
| `variance` / `var_pop` / `var_samp` | `var_samp` 族 | |
| `approx_count_distinct` | 同名 | |
| `collect_list` | `list` | |
| `collect_set` | 参数重构 | `list(DISTINCT x)` |
| `percentile` / `percentile_approx` / `approx_percentile` | `quantile_cont` | 近似语义,精确 percentile 可能偏差 |
| `count_if` | 参数重构 | `count(CASE WHEN p THEN 1 END)` |
| `bool_and` / `bool_or` / `every` / `some` / `any` | 同名 | |

## 窗口

`row_number` `rank` `dense_rank` `percent_rank` `cume_dist` `ntile` `lag` `lead` `first_value` `last_value` `nth_value` 全同名;`max_by`/`min_by` → `arg_max`/`arg_min`。`OVER (PARTITION BY ... ORDER BY ... ROWS|RANGE BETWEEN ...)` frame 完整支持。

## 高阶函数(Lambda)

| Spark 函数 | DuckDB 目标 | 备注 |
|---|---|---|
| `transform` | `list_transform` | |
| `filter` | `list_filter` | |
| `exists` / `forall` | `list_exists` / 参数重构 | |
| `reduce` | `list_reduce` | |
| `aggregate(arr, zero, merge)` | 参数重构 | zero 前置合并;**带 finish lambda 的 4 参形式不支持** |
| `transform_values` / `transform_keys` / `map_filter` | 参数重构 | map_entries 单变量 lambda 组合 |
| `map_zip_with(m1, m2, (k,v1,v2)->e)` | 参数重构 | 键并集 + `map_extract` 查值,缺键 → NULL |
| `zip_with` | **不支持** | `list_zip` 生成的 struct 字段名为空,lambda 引用不了 |

## 条件 / 类型 / 其他

| Spark 函数 | DuckDB 目标 | 备注 |
|---|---|---|
| `if(cond, a, b)` | `CASE WHEN` | |
| `nvl2(a, b, c)` | `CASE WHEN a IS NOT NULL` | |
| `equal_null(a, b)` | `IS NOT DISTINCT FROM` | |
| `isnull` / `isnotnull` | `IS NULL` / `IS NOT NULL` | |
| `coalesce` | 同名 | |
| `cast` | `CAST` | |
| `typeof` / `uuid` / `random` | 同名 | |
| `current_catalog` | `current_database()` | |
| `mon` / `fri` / `tue` / `wed` / `thu` / `sat` / `sun` | 参数重构 | 分区日期字面量辅助 |

## Java UDF 外挂(DuckDB 缺失的内置函数)

这三个函数通过 `DuckDbBuiltinUdfRegistrar` 注册为 **DuckDB 连接级 Java 标量函数**(DuckDB 1.5 JDBC `scalarFunction` builder),生成器以显式 CAST 透传(DuckDB binder 对 UDF 参数不做隐式转换):

| 函数 | Java 实现 | Spark 语义对齐 |
|---|---|---|
| `initcap` | 字符遍历 | 字母数字词首字母大写、其余小写:`HELLO-WORLD foo` → `Hello-World Foo` |
| `bround` | `BigDecimal.setScale(HALF_EVEN)` | 2.5→2、3.5→4、-2.5→-2;支持负 scale;1 参/2 参重载 |
| `conv` | BigInteger 进制转换 | 负数按 64 位补码(`conv(-255,10,16)`→`FFFFFFFFFFFFFF01`)、负 to_base 有符号输出(`-FF`)、64 位饱和、首个无效字符停读 |

注意:这些 UDF 挂在连接上,直接对底层 DuckDB 连接执行裸 SQL(如 BI 工具绕过翻译层)也可使用;但**新建连接**需经过 `DuckDbExecutor` 构造器才会注册。

## 已知限制清单

| 函数 | 限制 |
|---|---|
| `from_json` | MAP schema 不支持(DuckDB JSON schema 无 map 形式);schema 必须是字面量 |
| `percentile` 族 | 映射为 `quantile_cont`,精确 percentile 语义有近似偏差 |
| `zip_with` | 不支持(list_zip struct 字段名为空) |
| `aggregate` 4 参 | finish lambda 不支持 |
| `date_format` | 多个 `SSS` 段不支持;非常见 Java pattern token 可能漏译 |
| `substring_index` / `next_day` / `date_trunc` / `format_number` | 相应参数必须是字面量 |
| `encode` / `decode` | 仅 UTF-8 |
| `conv` 等 Java UDF | 需经 `DuckDbExecutor` 创建连接;工具直连 DuckDB 文件时函数不存在 |

除上表外,未列出的 Spark 函数会在翻译期以 `Unsupported expr: <name>` 明确报错(而不是静默错译);需要时优先考虑 `--function-map` 用户映射或 `--udf-config` Java UDF 扩展。
