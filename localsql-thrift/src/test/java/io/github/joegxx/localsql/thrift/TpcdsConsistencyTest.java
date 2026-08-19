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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * L3 differential consistency test over the full TPC-DS suites.
 *
 * Oracle design (DuckDB-bootstrapped, no external Spark cluster):
 *   - data: DuckDB's tpcds extension dsdgen(sf=0.01) creates all 24 physical
 *     tables with the full standard schema and deterministic rows, directly in
 *     the server's embedded DuckDB
 *   - expected: the ORIGINAL Spark SQL text executed natively by DuckDB
 *   - actual: the same text executed through our Thrift endpoint
 *     (Spark SQL -> IR -> analyze -> rewrite -> DuckDB SQL -> execute)
 *   - compare: both sides stringified identically, persisted as VARCHAR tables,
 *     bidirectional EXCEPT ALL must be empty (strict multiset equality)
 *
 * Any semantic drift introduced by our translation surfaces as a mismatch.
 */
class TpcdsConsistencyTest {

    private static final int PORT = 10097;
    private static ThriftQueryRunner runner;

    /**
     * Queries whose ORIGINAL text cannot execute on native DuckDB (reserved-word
     * aliases like 'returns'/'at' that DuckDB rejects unquoted). The translated
     * side still runs (we quote reserved words); the differential oracle is
     * unavailable for these, so they assert thrift-side success only.
     */
    private static final java.util.Set<String> ORACLE_UNAVAILABLE = java.util.Set.of(
            "tpcds/q77.sql", "tpcds-v2.7.0/q77a.sql", "tpcds/q90.sql");

    @BeforeAll
    static void setUp() throws Exception {
        runner = ThriftQueryRunner.start(PORT);
        runner.executor().run("INSTALL tpcds");
        runner.executor().run("LOAD tpcds");
        runner.executor().run("CALL dsdgen(sf=0.01)");
        // DuckDB's tpcds extension ships the 1.x schema; TPC-DS v2.7 queries
        // reference c_last_review_date (DATE). Add it as an all-NULL column so
        // both sides run against the same shape.
        try {
            runner.executor().run("ALTER TABLE customer ADD COLUMN c_last_review_date DATE");
        } catch (Exception ignored) {
            // column already exists
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (runner != null) runner.close();
    }

    static List<String> queryFiles() throws IOException {
        List<String> files = new ArrayList<>();
        for (String dir : new String[]{"tpcds-v2.7.0", "tpcds"}) {
            Path d = TpcdsQueryPipelineTest.resourceDirPublic(dir);
            try (var stream = Files.list(d)) {
                stream.map(p -> dir + "/" + p.getFileName())
                        .filter(n -> n.endsWith(".sql"))
                        .sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryFiles")
    void translatedResultMatchesNative(String relPath) throws Exception {
        String sql = readQuery(relPath);
        String id = relPath.replace("/", "_").replace(".sql", "").replaceAll("[^A-Za-z0-9_]", "_");
        boolean oracleAvailable = !ORACLE_UNAVAILABLE.contains(relPath);

        List<List<String>> expected;
        int cols;
        if (oracleAvailable) {
            // expected: native DuckDB execution, with Spark backquoted identifiers
            // normalized to DuckDB double quotes
            var nativeRes = runner.executor().execute(normalizeBackquotes(sql));
            expected = stringify(nativeRes.rows());
            cols = nativeRes.columns().size();
        } else {
            expected = List.of();
            cols = 0;
        }

        // actual: through the Thrift pipeline
        List<List<String>> actual;
        try {
            actual = runner.query(sql);
        } catch (Exception e) {
            fail(relPath + " thrift execution failed: " + e.getMessage());
            return;
        }

        if (!oracleAvailable) {
            // oracle cannot run the original text; just assert thrift succeeded
            assertTrue(true, relPath + " thrift ok (oracle unavailable)");
            return;
        }

        List<List<String>> exp = expected;
        assertEquals(exp.size(), actual.size(),
                () -> relPath + " row count differs (native=" + exp.size() + " thrift=" + actual.size() + ")");

        persist(id, "expected", exp, cols);
        persist(id, "actual", actual, cols);

        String expTable = "expected_" + id, actTable = "actual_" + id;
        var diff = runner.executor().execute(
                "SELECT count(*) FROM ((SELECT * FROM " + expTable + " EXCEPT ALL SELECT * FROM " + actTable + ") "
                        + "UNION ALL (SELECT * FROM " + actTable + " EXCEPT ALL SELECT * FROM " + expTable + "))");
        long mismatches = ((Number) diff.rows().get(0).get(0)).longValue();
        if (mismatches > 0) {
            var onlyExpected = runner.executor().execute(
                    "SELECT * FROM (SELECT * FROM " + expTable + " EXCEPT ALL SELECT * FROM " + actTable + ") LIMIT 5");
            var onlyActual = runner.executor().execute(
                    "SELECT * FROM (SELECT * FROM " + actTable + " EXCEPT ALL SELECT * FROM " + expTable + ") LIMIT 5");
            fail(relPath + ": " + mismatches + " mismatched rows. "
                    + "Only native: " + onlyExpected.rows() + " | Only translated: " + onlyActual.rows());
        }
        assertTrue(mismatches == 0L, relPath + ": translated result differs from native");
    }

    private static String normalizeBackquotes(String sql) {
        return sql.replace("`", "\"");
    }

    private static List<List<String>> stringify(List<List<Object>> rows) {
        List<List<String>> out = new ArrayList<>();
        for (List<Object> row : rows) {
            List<String> r = new ArrayList<>(row.size());
            for (Object v : row) r.add(v == null ? null : String.valueOf(v));
            out.add(r);
        }
        return out;
    }

    private static void persist(String id, String side, List<List<String>> rows, int cols) throws Exception {
        runner.executor().executeUpdate("DROP TABLE IF EXISTS " + side + "_" + id);
        StringBuilder create = new StringBuilder("CREATE TABLE " + side + "_" + id + " (");
        for (int i = 0; i < cols; i++) {
            if (i > 0) create.append(", ");
            create.append("c").append(i).append(" VARCHAR");
        }
        create.append(")");
        runner.executor().executeUpdate(create.toString());
        for (List<String> row : rows) {
            StringBuilder ins = new StringBuilder("INSERT INTO " + side + "_" + id + " VALUES (");
            for (int i = 0; i < cols; i++) {
                if (i > 0) ins.append(", ");
                String v = row.get(i);
                ins.append(v == null ? "NULL" : "'" + v.replace("'", "''") + "'");
            }
            ins.append(")");
            runner.executor().executeUpdate(ins.toString());
        }
    }

    private static String readQuery(String relPath) throws IOException {
        int slash = relPath.indexOf('/');
        Path dir = TpcdsQueryPipelineTest.resourceDirPublic(relPath.substring(0, slash));
        return Files.readString(dir.resolve(relPath.substring(slash + 1)));
    }
}
