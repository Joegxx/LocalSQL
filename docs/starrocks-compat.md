# StarRocks 方言兼容矩阵（docs/starrocks-compat.md）

> 三态定义：**✅ 支持**＝原文翻译并在 DuckDB 执行；**🔄 归一**＝StarRocks 语法在前端归一为 IR 等价形式，行为一致；**❌ 拒绝**＝语法能 parse 但前端/分析器显式报清晰错误（不是静默丢失）。
>
> 语法来源钉死 starrocks `3.5.21`（见 starrocks-compat-plan.md）。方言在连接时指定：`jdbc:hive2://host:10000/db;sql_dialect=starrocks`。

## 验证基线（2026-11-12）

- L1 翻译回归 `StarRocksFrontendPipelineTest`：官方 TPC-DS 99 条语料（103 份 SQL）全绿，`KNOWN_GAPS` 为空，strict 函数目录全绑定。
- L3 双方言孪生差分 `StarRocksTwinDifferentialTest`：同一文本双方言执行，双向 `EXCEPT ALL` 为 0。95 份全等价；8 份（q16/32/50/62/92/94/95/99）使用 `as "..."` 双引号别名——Spark 语法本就拒绝该文本，无孪生可比，仅断言 StarRocks 侧执行成功。
- Spark 基线零回退：排除三个 L3 端口测试后全量绿。

## DQL

| 特性 | 状态 | 说明 |
|---|---|---|
| SELECT / FROM / WHERE / GROUP BY / HAVING / ORDER BY | ✅ | 含 `HAVING` 无 GROUP BY 的隐式单组聚合 |
| LIMIT / OFFSET，含 `LIMIT a, b` | 🔄 | `LIMIT a,b` 归一为 `Limit(offset,limit)` |
| UNION [ALL] / INTERSECT / EXCEPT / MINUS | 🔄 | `MINUS` 按 Oracle 风格归一为 EXCEPT；INTERSECT 优先级按 StarRocks 语义 |
| CTE（WITH） | ✅ | CTE 列别名（`WITH x(a,b) AS ...`）❌ 拒绝 |
| ROLLUP / CUBE / GROUPING SETS | ✅ | TPC-DS 覆盖 |
| 窗口函数（含 frame：ROWS/RANGE、UNBOUNDED/CURRENT ROW/数值边界） | ✅ | TPC-DS 覆盖；`OVER` 仅接受内联 spec |
| 命名窗口（`WINDOW w AS ...` / `OVER w`）、聚合 `FILTER` 子句 | ❌ | 3.5.21 语法不含，parse 阶段即失败（无静默丢失） |
| JOIN：INNER/LEFT/RIGHT/FULL/CROSS/SEMI/ANTI、ON/USING | ✅ | RIGHT SEMI/ANTI 通过交换两侧归一为 LEFT SEMI/ANTI |
| 派生表 / VALUES 行内表 / 括号关系 | ✅ | 派生表列别名 ❌ 拒绝 |
| 子查询（含标量子查询、IN/EXISTS） | ✅ | 元组 `(a,b) IN (subquery)` ❌ 拒绝 |
| FROM DUAL | 🔄 | 等价无 FROM |
| 双引号字符串（`"abc"`、`as "alias"`） | 🔄 | StarRocks=字符串字面量（与 Spark 相反），归一为 StringLiteral / 字符串别名 |
| JSON 路径 `col -> '$.a'` | 🔄 | 归一为 `json_extract(col, '$.a')` |
| INTERVAL 字面量 / DATETIME 字面量 | 🔄 | DATETIME 'x' → TIMESTAMP 字面量 |
| `<=>` 空值安全比较 | ✅ | → `IS NOT DISTINCT FROM`（共享 lowering） |
| 数组/Map 构造器、下标 `a[i]` | 🔄 | 下标归一为 `element_at` |
| Lambda `x -> expr` | ✅ | 多值 lambda（`-> (a,b)`）❌ 拒绝 |
| `\|\|` | 🔄 | 固定 `sql_mode=34`（PPIPES_AS_CONCAT），`"\|\|` 按拼接；StarRocks 默认 LOGICAL_OR 语义不支持（探针锁定双模式） |
| 三段名 `hive_catalog.db.tbl` | 🔄 | catalog 前缀剥除，取 `db.tbl` |
| EXPLAIN | ✅ | 返回 DuckDB 译文 |
| QUALIFY | ❌ | 明确报错，建议子查询 + WHERE 改写 |
| PIVOT | ❌ | |
| LATERAL join / LATERAL 表函数 | ❌ | |
| 表函数（table function 族） | ❌ | |
| 时态查询（`FOR SYSTEM_TIME`） | ❌ | |
| 表后缀修饰（partitionNames/tabletList/replicaList/sampleClause/BEFORE） | ❌ | |
| TRACE / INTO OUTFILE | ❌ | |

## 类型与转换

| 特性 | 状态 | 说明 |
|---|---|---|
| 基础类型（BOOLEAN/TINYINT..BIGINT/INT/SIGNED/UNSIGNED/FLOAT/DOUBLE/DATE/CHAR/VARCHAR/STRING/TEXT/JSON→STRING/BINARY） | 🔄 | 按映射表归一到 IR 类型 |
| DATETIME | 🔄 | → TIMESTAMP |
| LARGEINT | 🔄 | → 128 位 HUGEINT |
| DECIMAL(p,s) / STRUCT | ✅ | |
| BITMAP / HLL / PERCENTILE | ❌ | StarRocks 专属聚合类型显式拒绝 |
| CAST / CONVERT(x, t) | 🔄 | CONVERT 归一为 Cast IR |

## 函数

| 特性 | 状态 | 说明 |
|---|---|---|
| 与 Spark 语义一致的内置函数 | ✅ | 共享 BuiltInFunctionCatalog + 共享 rewrite 表 |
| StarRocks 专属：group_concat、get_json_string/int/double、str_to_date、curdate | ✅ | `StarRocksFunctionCatalog`，strict 闸门绑定；`GROUP_CONCAT ... SEPARATOR`、聚合内 `ORDER BY` ❌ 拒绝 |
| 未知函数 | ❌ | strict 模式拒绝（StarRocks 会话不透传未知函数） |
| 函数降级 | ✅ | 方言作用域：StarRocks 专属 lowering 不影响 Spark 会话（负向回归保护） |
| UDF | ❌ | 首版不支持 UDF 路径 |
| `::`、ASOF、`* EXCLUDE`、`WITH RECURSIVE` | ❌ | 4.x 语法，3.5.21 语法不含，parse 即失败 |

## 管理语句（M3，pre-grammar 文本层）

| 特性 | 状态 | 说明 |
|---|---|---|
| USE catalog.db / USE db | ✅ | 取最后一段 |
| SHOW CATALOGS | ✅ | 单内建 catalog |
| SET CATALOG x | ✅ | StarRocks 专属，Spark 会话不识别 |
| SHOW DATABASES/TABLES、DESCRIBE 等 | ✅ | 与 Spark 会话共享模式集 |

## DDL / DML 与 UDF

❌ 全族不支持：StarRocks 前端仅 DQL（`QueryStatement`），模型 DDL（`PRIMARY KEY`/`DISTRIBUTED BY`/`BUCKETS` 等）、INSERT/UPDATE/DELETE 均显式拒绝。建表/导数据请用 Spark 方言会话或 `--import-ddl`。
