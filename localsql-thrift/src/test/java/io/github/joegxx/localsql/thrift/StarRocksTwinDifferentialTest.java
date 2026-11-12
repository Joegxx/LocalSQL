package io.github.joegxx.localsql.thrift;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * L3 双方言孪生差分：同一份 dsdgen(sf=0.01) 数据、同一台进程内 server、
 * <b>同一份 SQL 文本</b>（tpcds-starrocks 语料，ANSI 语法两个方言前端都能解析），
 * Spark 与 StarRocks 两个方言会话各执行一次，结果字符串化后双向 EXCEPT ALL
 * 必须为空（多重集相等，忽略行序）。差异即纯翻译分歧。
 *
 * <p>不能用两份语料互相配对：StarRocks 基准文档改写了部分查询常量（如 q6 的
 * d_year=2000 vs 2001），行差异来自文本而非翻译。
 *
 * <p>与 TpcdsConsistencyTest（translated vs DuckDB native oracle）不同，这里验证
 * 的是两种方言前端产出的翻译语义一致。需要真实端口与 tpcds 扩展，与其它 L3
 * 测试一样从快速基线排除。
 */
class StarRocksTwinDifferentialTest {

    private static final int PORT = 10096;
    private static ThriftServer server;
    private static ThriftQueryRunner sparkSession;
    private static ThriftQueryRunner starRocksSession;

    @BeforeAll
    static void setUp() throws Exception {
        var executor = new io.github.joegxx.localsql.duckdb.DuckDbExecutor();
        var store = new io.github.joegxx.localsql.duckdb.DuckDbCatalogStore(executor);
        var catalog = new io.github.joegxx.localsql.catalog.CatalogService(store);
        server = new ThriftServer(catalog, executor);
        server.start(PORT);
        Thread.sleep(800);
        // 同一台 server、两个方言会话：数据天然一致
        sparkSession = ThriftQueryRunner.connect(server, executor, catalog, PORT,
                Map.of("sql_dialect", "spark"));
        starRocksSession = ThriftQueryRunner.connect(server, executor, catalog, PORT,
                Map.of("sql_dialect", "starrocks"));
        // dsdgen 只建物理表；analyzer 绑定需要 Catalog 元数据（24 表 v2.7.0 schema）
        TpcdsCatalogFixture.register(catalog);
        executor.run("INSTALL tpcds");
        executor.run("LOAD tpcds");
        executor.run("CALL dsdgen(sf=0.01)");
        try {
            executor.run("ALTER TABLE customer ADD COLUMN c_last_review_date DATE");
        } catch (Exception ignored) {
            // column already exists
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (sparkSession != null) sparkSession.close();
        if (starRocksSession != null) starRocksSession.close();
        if (server != null) server.stop();
    }

    /** 孪生查询集 = StarRocks 语料全量（同一文本双方言执行）。 */
    static List<String> twinNames() throws IOException {
        return new ArrayList<>(new TreeSet<>(filenames("tpcds-starrocks")));
    }

    private static Set<String> filenames(String dir) throws IOException {
        try (var stream = Files.list(resourceDir(dir))) {
            var names = new TreeSet<String>();
            stream.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sql")).forEach(names::add);
            return names;
        }
    }

    /**
     * 两方言翻译语义有已知分歧的查询（原因必须注明），从差分断言中豁免。
     */
    private static final Set<String> KNOWN_DIFFERENT = new TreeSet<>(List.of());

    /**
     * StarRocks 基准语料使用双引号字符串别名（如 {@code as "order count"}）。
     * StarRocks 前端把双引号归一为字符串别名；Spark 语法在别名位只接受
     * 标识符/反引号（真实 Spark 3.2 同样拒绝这些文本），孪生等价比较无从建立。
     * 这些查询仍要求 StarRocks 会话成功执行，保留 L3 冒烟覆盖。
     */
    private static final Set<String> SPARK_UNPARSEABLE = new TreeSet<>(Set.of(
            "q16.sql", "q32.sql", "q50.sql", "q62.sql", "q92.sql", "q94.sql", "q95.sql", "q99.sql"));

    @ParameterizedTest(name = "{0}")
    @MethodSource("twinNames")
    void twinResultsMatch(String fileName) throws Exception {
        // 同一份文本（StarRocks 语料），两个方言前端分别翻译执行
        String sql = readQuery("tpcds-starrocks/" + fileName);

        // StarRocks 会话先执行：全部语料都要求 StarRocks 前端成功跑通
        List<List<String>> actual;
        try {
            actual = starRocksSession.query(sql);
        } catch (Exception e) {
            fail(fileName + " starrocks session failed: " + e.getMessage());
            return;
        }
        if (SPARK_UNPARSEABLE.contains(fileName)) {
            return; // 无 Spark 孪生（方言专属语法）；starrocks 执行已在上方断言
        }
        List<List<String>> expected;
        try {
            expected = sparkSession.query(sql);
        } catch (Exception e) {
            fail(fileName + " spark session failed: " + e.getMessage());
            return;
        }
        if (KNOWN_DIFFERENT.contains(fileName)) {
            return; // recorded divergence; see comment above
        }

        assertEquals(expected.size(), actual.size(),
                () -> fileName + " row count differs (spark=" + expected.size()
                        + " starrocks=" + actual.size() + ")");

        // 双方都为空即一致；persist 需要至少一列
        if (expected.isEmpty()) {
            return;
        }
        String id = fileName.replace(".sql", "").replaceAll("[^A-Za-z0-9_]", "_");
        int cols = expected.get(0).size();
        persist("twin_spark_" + id, expected, cols);
        persist("twin_sr_" + id, actual, cols);
        var diff = sparkSession.executor().execute(
                "SELECT count(*) FROM ((SELECT * FROM twin_spark_" + id + " EXCEPT ALL SELECT * FROM twin_sr_" + id + ") "
                        + "UNION ALL (SELECT * FROM twin_sr_" + id + " EXCEPT ALL SELECT * FROM twin_spark_" + id + "))");
        long mismatches = ((Number) diff.rows().get(0).get(0)).longValue();
        if (mismatches > 0) {
            var onlySpark = sparkSession.executor().execute(
                    "SELECT * FROM (SELECT * FROM twin_spark_" + id + " EXCEPT ALL SELECT * FROM twin_sr_" + id + ") LIMIT 5");
            var onlySr = sparkSession.executor().execute(
                    "SELECT * FROM (SELECT * FROM twin_sr_" + id + " EXCEPT ALL SELECT * FROM twin_spark_" + id + ") LIMIT 5");
            fail(fileName + ": " + mismatches + " mismatched rows. "
                    + "Only spark: " + onlySpark.rows() + " | Only starrocks: " + onlySr.rows());
        }
    }

    private static Path resourceDir(String name) {
        return TpcdsQueryPipelineTest.resourceDirPublic(name);
    }

    private static String readQuery(String relPath) throws IOException {
        int slash = relPath.indexOf('/');
        Path dir = resourceDir(relPath.substring(0, slash));
        return Files.readString(dir.resolve(relPath.substring(slash + 1)));
    }

    private static void persist(String table, List<List<String>> rows, int cols) throws Exception {
        starRocksSession.executor().executeUpdate("DROP TABLE IF EXISTS " + table);
        StringBuilder create = new StringBuilder("CREATE TABLE " + table + " (");
        for (int i = 0; i < cols; i++) {
            if (i > 0) create.append(", ");
            create.append("c").append(i).append(" VARCHAR");
        }
        create.append(")");
        starRocksSession.executor().executeUpdate(create.toString());
        for (List<String> row : rows) {
            StringBuilder ins = new StringBuilder("INSERT INTO " + table + " VALUES (");
            for (int i = 0; i < cols; i++) {
                if (i > 0) ins.append(", ");
                String v = row.get(i);
                ins.append(v == null ? "NULL" : "'" + v.replace("'", "''") + "'");
            }
            ins.append(")");
            starRocksSession.executor().executeUpdate(ins.toString());
        }
    }
}
