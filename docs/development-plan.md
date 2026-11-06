# LocalSQL 开发计划：正确性修复与模块边界收敛

更新日期：2026-09-10。

状态：项目说明已更新；本文是待实施方案，以下代码任务均未开始。审查基线不是本计划的实施结果，新接口、新模块和存储布局也不代表已经存在。

目标：让已支持的 SQL 经过可验证的名称和类型绑定，失败时保持物理数据、持久化元数据与内存 Catalog 一致，并使正式产物和 Thrift 行为符合声明能力。

## 1. 范围与优先级

| 编号 | 工作项 | 优先级 / 性质 | 交付目标 |
|---|---|---|---|
| A1 | Generator 中的语义转换迁入 Rewrite | 架构主线 | Generator 只序列化已解析、已降级 IR |
| A2 | CatalogService 优化 | 架构主线 | 纯逻辑元数据、统一写入口、可恢复缓存 |
| A3 | 函数转换统一 | 架构主线 | Analyzer 绑定，Rewrite 转换，每个函数只有一个转换归属 |
| F1 | Analyzer 名称解析与类型绑定 | P1 | 错库、缺列、歧义在执行前拒绝，输出 schema 正确 |
| F2 | DDL 回滚与内存 Catalog 一致性 | P1 | 失败后物理库、store、缓存一致 |
| F3 | UDF 正式产物与普通查询兼容 | P1 | JAR 可执行 UDF，未使用 UDF 的查询不受配置影响 |
| F4 | 语法被接受后静默丢弃 | P1 | 保留语义或明确拒绝，不静默返回错误结果 |
| F5 | Thrift 元数据、状态与生命周期 | P1/P2 | 修复崩溃、结果类型、状态、取消与资源回收 |
| F6 | 数据库级元数据持久化 | P2，随 A2/F2 实施 | 空库和数据库属性可恢复，旧库可迁移 |

范围约束：

- 不新增 optimizer、成本模型、分布式执行、用户级事务/ACID、认证、多引擎或更多 DML。
- 修复现有内部事务的原子性，不等于提供用户级事务功能。
- DDL 导入误删是独立 P1：失败清理可能按裸表名删除其他逻辑库的已有物理表。它不在本次指定的六项主线中；修复前只允许在可丢弃副本上验证导入，不得因本计划完成而宣称整体导入安全。
- 本文建议新增接口和数据库元数据存储；实际修改旧文件库前，须确认兼容、备份与迁移策略。本轮不执行数据迁移。

## 2. 目标职责与契约

| 层 | 负责 | 不负责 |
|---|---|---|
| Parser | SQL → ParseTree、语法错误位置 | Catalog 查询、函数转换、执行 |
| Spark AST Builder | ParseTree → IR、保留语法信息、拒绝无法表达的语法 | 后端 SQL、查表、表达式求值 |
| Analyzer | 作用域、表/列/函数绑定、类型推导、语义检查 | DuckDB 名称猜测、SQL 生成、Gateway 调用 |
| Rewrite | 已绑定 IR 的等价转换与目标方言降级 | Catalog/JDBC、Spark ParseTree、加载 UDF 类 |
| Generator | 已降级 IR → SQL、引号/类型名/括号序列化 | 函数转换、参数重排、lambda 重写、猜名字和类型 |
| Catalog/CatalogService | 逻辑元数据、查询、逻辑变更与快照发布 | JDBC、物理名称、运行时 SQL 和事务连接 |
| Runtime 编排 | 事务、物理名称绑定、执行阶段、Gateway、元数据发布 | 将语义解析塞进 Executor 或协议层 |
| Executor / Gateway | 执行 SQL / 已绑定 UDF 批次 | 解析查询或处理 IR |
| Thrift | Session、Operation、协议类型、游标与生命周期 | 重复实现语义解析或 DDL 编排 |

共同约束：

1. IR 是唯一查询表示，不用 Raw SQL 节点隐藏待迁移的语义逻辑。
2. `analyze` / `rewrite` 可以返回新树，调用方必须使用返回值，不依赖永远原地修改。
3. Analyzer 后检查语义绑定；Rewrite/物理绑定后检查后端可序列化性。Generator 不做补救解析。
4. Catalog 不依赖 DuckDB、Thrift、UDF runtime；Rewrite 不新增 Parser、Catalog 或 JDBC 依赖。
5. 短期保留 SqlPipeline 编排入口，先按职责拆类；独立 runtime 模块留待公共嵌入式 API 有实际需要时评估。

## 3. 实施顺序

以下是依赖顺序，不是工期承诺。每阶段可拆为多个可独立验收的提交。

| 阶段 | 工作内容 | 前置条件 | 阶段验收 |
|---|---|---|---|
| M0 | 固化缺陷复现、现有结果和模块边界，建立产物测试入口 | 无 | 已确认缺陷都有能暴露旧行为的测试 |
| M1 | A2 + F2 + F6：职责分离、缓存发布、数据库持久化 | M0；确认存储兼容方案 | 失败注入与文件库恢复通过 |
| M2 | A3 函数元数据契约 + F1：作用域、类型与 resolved IR | 稳定的 Catalog 查询接口 | 错库/缺列/歧义拒绝，合法 CTE/别名/子查询通过 |
| M3 | A1 + A3：分函数族迁移转换及配置；F4 全面审计 | 绑定和类型契约稳定 | Generator 无函数语义转换，不再静默丢弃语义 |
| M4 | F3 + F5：UDF 产物、协议类型、状态与生命周期 | 小修复可提前；完整验收依赖 M1–M3 | 实际 JAR、直接入口、真实 Thrift 验证通过 |
| M5 | 总体验收、删除兼容路径、更新说明 | 目标任务完成 | 测试和支持范围可追溯 |

可提前的小提交：GetTypeInfo 空值崩溃、无 UDF 查询误入拆分器、未支持 LIMIT/SORT BY 显式拒绝、UDF 运行依赖修正。这些不必等待整体架构迁移。

## 4. A1：Generator → Rewrite 迁移方案

主要入口：[DuckDbSqlGenerator](../localsql-duckdb/src/main/java/io/github/joegxx/localsql/duckdb/DuckDbSqlGenerator.java)、[RewriteEngine](../localsql-rewrite/src/main/java/io/github/joegxx/localsql/rewrite/RewriteEngine.java)。

| 当前逻辑 | 目标归属 | 修复方式 |
|---|---|---|
| FN_MAP、函数别名、目标函数选择 | Rewrite | 按已绑定函数身份输出目标调用 IR |
| 参数重排、补参、CAST、CASE、NULL 保护 | Rewrite | 构造显式表达式节点，不拼 SQL 模板 |
| lambda 变量替换、MAP/ARRAY 高阶函数降级 | Rewrite | 按绑定作用域替换，避免变量捕获和名字冲突 |
| 日期格式、JSON schema 目标表达 | Analyzer + Rewrite | Analyzer 解析类型/常量约束，Rewrite 产生目标常量与表达式 |
| 自定义 rename/template | 配置载入 + Rewrite | 载入时验证并编译为规则，不在 Generator 替换原始字符串 |
| 关系结构及作用域语义调整 | Rewrite | 必要时先补 IR 表达，再迁移 |
| 引号、字面量转义、类型拼写、SELECT/JOIN/窗口/frame/括号 | Generator | 保留纯序列化职责 |
| 连接级 Java 函数注册 | DuckDB runtime | 不搬进 Rewrite，只把调用选择和 CAST 表达成 IR |

步骤：

1. 建立转换清单：源函数、参数约束、返回类型、NULL 行为、旧分支、目标规则和测试。
2. 依次迁简单改名、参数/CAST/CASE、日期/JSON、lambda 与关系结构；不一次搬整个 switch。
3. 每个函数迁入 Rewrite 后关闭对应 Generator 分支。过渡期未迁函数可保留旧路径，但不能同时执行新旧规则。
4. 保留 Query/Source 区分、emitChildSource 括号、JOIN 别名与 WHERE/HAVING 回归。新 IR 未表达前，不机械删除 WHERE 内联逻辑。
5. 最后删除 FN_MAP、特殊函数转换分支和 Generator 原始模板入口。

验收：

- [ ] Rewrite 有输入/输出 IR 结构断言，Generator 只接收可序列化 IR，且不修改输入树。
- [ ] 无二次 rename、重复 CAST、重复参数扩展；每个源函数只有一个转换归属。
- [ ] Generator 不调用 IdentifierSubstituter 或 schema 语义解析；Rewrite 不越过模块依赖边界。
- [ ] 函数、CTE、窗口、JOIN 别名与 TPC-DS 回归通过；行为变化有语义依据，不只更新快照。

## 5. A2：CatalogService 优化方案

主要入口：[CatalogService](../localsql-catalog/src/main/java/io/github/joegxx/localsql/catalog/CatalogService.java)、[CatalogStore](../localsql-catalog/src/main/java/io/github/joegxx/localsql/catalog/CatalogStore.java)、[DuckDbCatalogStore](../localsql-duckdb/src/main/java/io/github/joegxx/localsql/duckdb/DuckDbCatalogStore.java)。

1. CatalogStore 只保留逻辑数据库、表、列、属性的持久化。RuntimeInfo 及 save/loadRuntimeInfo 移至独立 runtime store 契约，首期放在 DuckDB runtime 所属模块。
2. CatalogService.renameTable 只产生逻辑重命名，删除正则修补 create_sql。物理 RENAME 和运行定义更新由编排层处理，基于结构化定义/IR 或物理元数据，不修改任意 SQL 字符串。
3. 收拢 SqlPipeline 中直接更新 Catalog 和调用 store.saveTable 的写入，统一为可暂存的逻辑变更；查询接口与写接口分离。
4. 引入完整快照或 copy-on-write 状态，包含数据库、表、列、属性、ID 分配。内部集合不可被外部引用修改；保留稳定查询门面，避免 Analyzer 持有过期 Catalog 对象。
5. 集中处理大小写规范化并保留展示名称；create/replace/if exists/重复注册语义明确，不依赖 Map.put 隐式覆盖。
6. 与 F2/F6 一起实现；无 store 的纯内存模式也要具备相同失败恢复语义。

验收：

- [x] Catalog 无 JDBC、DuckDB SQL、DDL 正则或 RuntimeInfo（RuntimeStore 独立接口；rename 的 replay DDL 从 DuckDB 物理元数据读取）。
- [x] rename/refresh/properties/drop/database 使用统一变更路径，无旁路写缓存（SET/UNSET TBLPROPERTIES 已收编进 CatalogService.updateTable）。
- [ ] Analyzer、SHOW/DESCRIBE、Thrift 元数据读取同一已提交快照（快照协议已建，旁路读者未完全纳入）。
- [x] store 写入失败不污染缓存（各写入口失败回滚内存），调用方不能通过集合引用修改内部元数据（record 防御性拷贝；registerTable 重复注册显式拒绝）。

## 6. A3：函数绑定与转换统一方案

主要入口：RewriteEngine、DuckDbSqlGenerator、[UdfRegistry](../localsql-udf-api/src/main/java/io/github/joegxx/localsql/udf/api/UdfRegistry.java)、[FunctionMapLoader](../localsql-app/src/main/java/io/github/joegxx/localsql/app/FunctionMapLoader.java)。

1. Analyzer 接收只读函数元数据：身份、别名、参数数目/类型、返回类型、标量/聚合/窗口/表函数类别、UDF 标识。app 可组装元数据适配器，但不能传入 Gateway、ClassLoader 或 Hive 执行类。
2. IR 保留绑定身份和签名，区分源函数、目标函数和 UDF；Rewrite 输出标记为已降级的调用，不再次匹配源规则。
3. 自定义映射按源函数身份覆盖内置规则。UDF 与内置同名同签名冲突必须有显式策略，否则报歧义；不依赖注册顺序，也不允许早期 rename 绕过原名配置。
4. 自定义模板载入时编译成带参数占位符的受限表达式 IR，校验占位符、参数数目与类型规则。不能表达的旧配置给迁移错误，禁止 Raw SQL 或静默透传兜底。
5. 配置前端/适配器不放入 Generator，Rewrite 不直接依赖 Spark Parser；配置载入编排传入已编译规则。
6. 未注册函数明确拒绝。若保留原生 DuckDB 函数能力，需要显式登记或受控兼容策略，不能让未知函数全部透传。

验收：

- [ ] 源名覆盖、别名、参数数量、重载、类别与同名冲突均有测试。
- [ ] DQL、INSERT SELECT、CTAS、CREATE VIEW 的函数处理一致；不支持的 Java UDF 位置仍显式拒绝。
- [ ] 旧配置迁移有清单和诊断；最终 Analyzer 只绑定、Rewrite 独占转换、Generator 只序列化。

## 7. F1：Analyzer 名称解析与类型绑定

主要入口：[SemanticAnalyzer](../localsql-analyzer/src/main/java/io/github/joegxx/localsql/analyzer/SemanticAnalyzer.java)、[Project.output](../localsql-ir/src/main/java/io/github/joegxx/localsql/ir/relation/Project.java) 及其他 Relation.output。

审查已复现：已有 default.t 时查询 no_such_database.t 或切换空库后查询裸名 t 仍能读到它；INT 列的普通 Identifier 分析后仍是 UnknownType。

1. 建立分层 Scope，覆盖当前目录/数据库、FROM 关系、表别名、CTE、派生表输出、相关子查询与 lambda 局部变量。显式限定名必须存在，裸名只在当前库查找，不回落 default。
2. 区分真实表、CTE 和派生关系；不能仅在 visitTableScan 加“查不到就报错”而破坏合法 CTE。
3. 将 Identifier 绑定为带关系实例/列身份的 AttributeReference。self join 两个关系实例不能只共享 tableId；实现星号展开、歧义检查和相关引用。
4. 正确计算 Project/Aggregate/SubqueryAlias/Join/With 输出，不能让 Project.output 返回全部子列；处理 ORDER BY/HAVING 中别名可见性、JOIN USING、窗口输出。
5. 传播 DataType、nullable、Decimal 精度/scale，函数类型来自 A3 签名；移除 Unknown → STRING 的伪推导。不支持的类型明确拒绝。
6. DQL、INSERT、LIKE、CTAS、视图、ALTER/RENAME 使用同一解析结果，不由编排层再次按原名/default 库查找。
7. Analyzer 只产生逻辑表身份；runtime 根据 runtime store 绑定物理名称并输出可序列化 IR。Generator 不取名字最后一段。首期仍可明确拒绝跨库同名表，物理命名迁移另行评估。
8. 严格绑定闸门启用前，给 TPC-DS 翻译测试补齐 24 表 Catalog fixture，不扩大跳过列表或把生产 Analyzer 退回宽松模式。

验收：

- [ ] 错库/错表/缺列/歧义在 Executor 调用前失败，无物理副作用。
- [ ] USE、限定名、LIKE、CTE、子查询、self join、别名、JOIN USING 均有正反例。
- [ ] SELECT id AS x 只输出 x，类型正确；聚合、CAST、Decimal 输出 schema 可断言。
- [ ] Generator 入口不存在未绑定用户 Identifier；重写生成的 IR 同样通过后端校验。

## 8. F2：DDL 事务与内存 Catalog 一致性

主要入口：[SqlPipeline](../localsql-thrift/src/main/java/io/github/joegxx/localsql/thrift/SqlPipeline.java) 的 inTransaction 与各 DDL 处理方法。

审查已复现：CREATE TABLE ghost (id INT) AS SELECT 'not_int' 失败后仅内存保留 ghost；替换视图失败后物理视图和 store 恢复，缓存却丢失旧视图。

1. 编排层统一事务：物理 DDL、逻辑 store、runtime store 使用同一事务连接，内部方法不能隐式提交或嵌套 BEGIN。
2. 首选“工作快照 + 提交后发布”。执行期间校验读取独立工作快照，COMMIT 成功后原子替换已提交快照，不能为 CTAS 的 INSERT 校验提前污染全局 Catalog。
3. 内存模式同样使用工作快照，失败直接丢弃。过渡期如用快照恢复，必须覆盖数据库、表、属性及 ID 状态。
4. SHOW/DESCRIBE/GetTables/GetColumns 等旁路读者纳入快照与同步协议，不能只依赖 SqlPipeline.execute 的 synchronized。
5. 不吞掉 CASCADE 等关键物理错误。提交失败区分已回滚和状态未知；未知时从已提交 store 重载，重载失败则阻止继续执行并报告恢复失败。
6. 覆盖 CTAS、替换视图、多列 ALTER、分区表重建、属性、RENAME、DROP DATABASE CASCADE，而非只修两个样例。

验收：

- [ ] 在物理操作、store、runtime store、提交阶段注入失败，同时断言物理数据/schema、两个 store 与内存快照。
- [ ] 无幽灵表；失败替换视图保留旧定义；失败 ALTER 不留下部分列变更。
- [ ] 当前进程后续查询和文件库重启结果一致，不依赖重启治愈缓存。
- [ ] 并行元数据请求看不到工作快照，内存模式通过同样的恢复测试。

## 9. F3：UDF 产物与普通查询兼容

主要入口：[udf-runtime POM](../localsql-udf-runtime/pom.xml)、[Main](../localsql-app/src/main/java/io/github/joegxx/localsql/app/Main.java)、[UdfProjectionSplitter](../localsql-rewrite/src/main/java/io/github/joegxx/localsql/rewrite/UdfProjectionSplitter.java)。

审查已复现：实际 runtime.jar 配置 udfs: [] 即缺少 ObjectInspector；测试依赖补齐后，注册未使用 UDF 会误拒绝普通 SELECT *。

1. 首期保持进程内 Gateway，在 app 运行/打包路径显式提供 Hive 运行依赖；检查传递依赖、日志冲突、Java 21 参数与许可证，不让 IR/Analyzer/Catalog 引入 Hive。
2. 若体积或依赖冲突不可接受，再决定独立 worker 发行方案，不能以不存在的 worker 为理由继续使用 provided。用户 UDF JAR 仍外置，与 Gateway 的 Hive API 依赖分开处理。
3. 拆分器先确认查询确实使用已绑定 UDF，再应用放置限制；注册 UDF/UDTF 不应影响无关 SELECT。
4. 检查 LIMIT/ORDER BY 包装顺序、投影别名、UDF 输出依赖；暂不支持的嵌套、排序和放置位置明确拒绝，不先排旧输入再声称按 UDF 输出排序。
5. 关闭 Gateway、ClassLoader、GenericUDF 生命周期资源；UDTF 临时表在成功、失败、取消后清理，Main 停止路径实际调用释放接口。
6. 逐行 deadline 检查不能打断阻塞 evaluate。近期明确限制；任意 UDF 强制终止需要进程边界，不把线程 cancel 宣称为强超时。

验收：

- [ ] 实际发行产物验证无 UDF、空配置、GenericUDF、simple UDF、UDTF。
- [ ] 测试启动真实子 JVM，fixture UDF JAR 外置，不能把 Maven test classpath 拼回产物。
- [ ] 未使用 UDF 时 SELECT *、CTE、排序和 LIMIT 与无配置结果一致。
- [ ] 支持/拒绝位置、错误诊断、资源清理和 timeout 声明均有验证。

## 10. F4：拒绝静默丢失的语义

主要入口：[SparkAstBuilder](../localsql-spark/src/main/java/io/github/joegxx/localsql/spark/SparkAstBuilder.java) 的 visitQueryOrganization 与 SparkExpressionBuilder。

审查已复现：LIMIT 1 + 1 返回全部行，SORT BY id DESC 未生成排序。默认 NULL 排序也需纳入 Spark 基准核验。

1. 审计 grammar 字段、未访问分支、return null、空实现和默认值，建立“支持 / 拒绝 / 明确存储提示兼容”清单。
2. 首期明确拒绝非字面量 LIMIT、未实现的 SORT/DISTRIBUTE/CLUSTER BY，并带错误位置；不无条件将它们改成 ORDER BY。
3. 若支持常量 LIMIT，AST 保留表达式，Analyzer 检查整数、非负、可求值约束，Rewrite 在受限规则内求值。不在 Parser/Generator 做计算，也不扩成通用 optimizer。
4. ORDER BY 与窗口排序的默认 NULL 顺序一起核验，在 IR 显式表达，补 NULL、重复值、ASC/DESC、LIMIT 测试。
5. 已约定的 PARTITIONED BY、CLUSTERED/SKEWED 存储提示与影响行值、行数、顺序的语义分开；允许忽略的提示需有明确策略或诊断。

验收：

- [ ] 已解析语义都有 IR/执行行为，或在执行前明确拒绝；LIMIT 不会被忽略。
- [ ] 不支持语义测试断言 Executor 未被调用；排序比较有序序列，不只比较多重集。
- [ ] 存储提示兼容清单明确，不修改 LETTER 与 UpperCaseCharStream 机制。

## 11. F5：Thrift 协议正确性

主要入口：[LocalSqlThriftService](../localsql-thrift/src/main/java/io/github/joegxx/localsql/thrift/LocalSqlThriftService.java)、[ThriftServer](../localsql-thrift/src/main/java/io/github/joegxx/localsql/thrift/ThriftServer.java)、[DuckDbExecutor](../localsql-duckdb/src/main/java/io/github/joegxx/localsql/duckdb/DuckDbExecutor.java)。

审查已复现：GetTypeInfo 因 List.of 包含 null 抛 NPE；INT 结果声明 STRING；失败 Operation 返回 FINISHED；关闭 Session 后仍可 Fetch。

1. 修 GetTypeInfo nullable 行构造；检查元数据列定义/行宽、GetTables 类型过滤、GetColumns ordinal/nullability/precision/scale。
2. QueryResult 增加列描述：名称、逻辑/JDBC 类型、精度、scale、nullable。普通查询从 JDBC metadata 获取，不依赖首行，空结果保留类型。
3. 管理语句、DDL count、UDF/UDTF 同样产生列描述；TTypeDesc、列式值和 null bitmap 一致。复杂类型降级明确声明，不统一冒充 STRING。
4. Operation 记录 Session、稳定 queryId、状态、错误、游标和释放动作；无效句柄明确拒绝，不回落 default 会话。
5. 状态按实际执行更新。异步、超时未支持时明确拒绝，不忽略请求；Cancel 未实现前不能虚报成功。
6. Executor 暴露执行句柄供取消，Thrift 不接触 JDBC；取消不等待 execute 的同一大锁。取消 DDL 走 F2 回滚，取消和关闭幂等。
7. CloseSession 级联清理 Operation；明确 FETCH_FIRST/NEXT、空结果和无效 orientation 行为，限制结果缓存并逐步使用可关闭分页游标。
8. start 返回就绪/失败反馈，绑定失败向调用者抛错而不是 System.exit；测试使用临时端口和 readiness，替代固定端口与 sleep。

验收：

- [ ] 方法级测试覆盖已实现接口的成功、空值、无效句柄、不支持请求，不要求全部监听端口。
- [ ] 真实 Thrift/JDBC 验证数值、Decimal、Boolean、日期/时间戳、NULL、空结果及元数据。
- [ ] 失败/取消/关闭/超时状态准确，Session 隔离及关闭后的资源释放可验证。
- [ ] 分页无重复或漏行，启动失败不退出宿主 JVM，取消与 DDL 回滚有集成测试。

## 12. F6：数据库级元数据持久化

审查已复现：创建空数据库后重新加载 CatalogService(store)，该库消失；description/location/properties 没有持久化恢复路径。

1. 扩展逻辑 CatalogStore 的数据库 save/load/list/delete；不以默认 no-op 写方法假装已支持持久化。
2. 建议在现有四表之外新增 catalog_database，保存规范化名称、展示名、description、location、结构化 properties，并增加可识别的 schema version 信息。具体 DDL 在实施前评审。
3. 新库初始化 default。旧库按版本迁移，从 catalog_table 推导已有库并补 default；旧版未保存的空库与属性无法恢复，明确局限，不伪造原值。
4. 启动先加载数据库再加载表；仅迁移旧库时从表推导数据库，不在每次启动静默重建缺失对象。
5. CREATE/ALTER/DROP DATABASE 走 F2 事务与缓存入口；CASCADE 包含物理对象、逻辑表、数据库及 runtime 记录。
6. 升级幂等、具备备份与失败恢复；未知新版本拒绝写入。首期不承诺旧二进制安全读取新库，降级依赖升级前备份。

验收：

- [x] 空库、带表库、description/location/properties 重开后保留（databasesSurviveRestart；重启经重开 CatalogService(store) 模拟）。
- [x] ALTER/DROP/CASCADE 重启前后一致，default 保护有效（databasesSurviveRestart；default 由 ensureDefaultDatabase 保证存在）。
- [x] 新库、旧四表库、重复迁移、未知版本都有测试（legacyStoreMigratesDatabasesFromTables、futureSchemaVersionIsRejected）；中途失败尚未有专项注入测试。
- [x] 引号、等号、多行属性值无损恢复（databasesSurviveRestart 覆盖 a=b 与 multi line；库属性与表属性同一 k/v 行存储）。

## 13. 测试策略与审查基线

### 2026-09-10 审查基线

- Java 21 下 mvn -o package -DskipTests 成功。
- 排除七个端口相关测试类后，455 项测试通过，0 failure/error。
- 全量测试因沙箱禁止 bind 中断，随后 ThriftServer 的 System.exit 退出测试 JVM；未完成真实端口 TPC-DS 差分。
- TPC-DS 为 135 份 SQL，翻译测试另有一个汇总测试，共 136 项。oracle 不可用的查询仅验证执行，不能计为结果一致。

这些记录来自审查，不是修复后的验收结果。先确认 java -version 与 mvn -version 均使用 Java 21；离线模式仅适用于依赖已缓存的环境。

```bash
mvn -o package -DskipTests

# 受限环境子集，不是全量测试替代品
mvn -o test \
  '-Dtest=!TpcdsConsistencyTest,!TpcdsGoldenResultTest,!ThriftSaslTest,!DdlDmlPipelineTest,!ThriftServerSmokeTest,!DatabaseAlterPipelineTest,!AdminShowCreateTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# TPC-DS 翻译回归
mvn -pl localsql-thrift -am test \
  -Dtest=TpcdsQueryPipelineTest -Dsurefire.failIfNoSpecifiedTests=false

# 在允许端口监听且扩展可用的环境运行
mvn clean test
mvn -pl localsql-thrift -am test \
  -Dtest=TpcdsConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false
```

必须补齐的测试维度：

- 单元：Scope、Relation.output、函数绑定、Rewrite、纯 Generator、Catalog 快照、协议编码。
- 执行：直接入口测试，不为所有 DDL/类型测试强制开端口。
- 故障：物理/store/runtime/提交失败注入，断言完整状态而不只断言抛异常。
- 恢复：真正文件库关闭重开、迁移、数据库属性与运行定义一致性。
- 产物：真实发行 JAR、外置 fixture UDF JAR、子 JVM，不借用测试 classpath。
- 语义：保留 DuckDB 差分，并补 Spark 3.2 参考结果或带来源/配置/时区的离线 golden；分别断言值、类型和有序结果。
- 架构：模块依赖与 forbidden imports 检查，防止 Generator/Catalog 再次混入语义或 runtime 逻辑。

## 14. 完成标准

- [ ] A1–A3、F1–F6 验收均有代码、测试及执行记录，完成后才勾选。
- [ ] 全量验证在允许端口监听的环境执行，未验证项单独列出。
- [ ] 旧 Generator 转换、Catalog runtime SQL、旁路缓存写入按迁移清单移除。
- [ ] 实际产物不借用测试依赖，旧库升级不丢数据，失败后当前进程可安全继续。
- [ ] README、AGENTS、usage、functions、udf-plan 的当前状态与目标一致，不保留过期测试声明。
- [ ] 独立的导入误删 P1 保持可见，未修复前不宣称导入安全。

建议第一个实现提交：M0 最小回归，加 F4 显式拒绝止错与 F5 GetTypeInfo 修复；随后进入 A2/F2/F6。Generator 系统迁移在绑定与 IR 契约稳定后按函数族推进。
