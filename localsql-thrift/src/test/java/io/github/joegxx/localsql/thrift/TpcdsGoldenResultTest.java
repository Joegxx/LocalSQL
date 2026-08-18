package io.github.joegxx.localsql.thrift;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 result-consistency test: execute TPC-DS Spark SQL through the real Thrift
 * endpoint and compare fetched rows against golden expected results.
 *
 * Architecture (all DuckDB-bootstrapped):
 *   - dataset: TpcdsMiniData registers deterministic mini TPC-DS tables
 *   - expected: golden rows calibrated by hand against Spark semantics on the
 *     mini dataset, persisted as VARCHAR tables expected_qN in DuckDB
 *   - actual: rows fetched over Thrift, persisted as actual_qN in DuckDB
 *   - compare: bidirectional EXCEPT ALL must be empty (strict multiset equality)
 *
 * The golden layer is designed to be refreshed by a real Spark runner later;
 * hand-calibration is the interim source of truth.
 */
class TpcdsGoldenResultTest {

    private static final int PORT = 10098;
    private static ThriftQueryRunner runner;

    @BeforeAll
    static void setUp() throws Exception {
        runner = ThriftQueryRunner.start(PORT);
        TpcdsMiniData.register(runner.catalog(), runner.executor());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (runner != null) runner.close();
    }

    record GoldenCase(String queryId, List<List<String>> expected) {}

    /**
     * Golden expectations, hand-derived with Spark semantics over TpcdsMiniData.
     * Consult the data comments in TpcdsMiniData when reviewing these values.
     */
    static List<GoldenCase> cases() {
        return List.of(
                // q3: d_moy=11, i_manufact_id=128
                // rows: (1,10,10.5),(1,10,20.0),(1,11,5.0),(2,10,7.25) qualify
                new GoldenCase("q3", List.of(
                        List.of("1999", "100", "brandA", "30.5"),
                        List.of("1999", "101", "brandB", "5.0"),
                        List.of("2000", "100", "brandA", "7.25"))),
                // q42: d_moy=11, d_year=2000, i_manager_id=1 -> only (2,10,7.25)
                new GoldenCase("q42", List.of(
                        List.of("2000", "1", "Books", "7.25"))),
                // q15: d_qoy=2 AND d_year=2001; zip-prefix OR state OR price>500
                new GoldenCase("q15", List.of(
                        List.of("85669", "400.0"),
                        List.of("99999", "700.0"))));
    }

    static List<String> caseIds() {
        return cases().stream().map(GoldenCase::queryId).toList();
    }

    @ParameterizedTest(name = "tpcds {0}")
    @MethodSource("caseIds")
    void resultMatchesSparkGolden(String queryId) throws Exception {
        GoldenCase c = cases().stream().filter(x -> x.queryId().equals(queryId)).findFirst().orElseThrow();
        String sql = readQuery(queryId);
        List<List<String>> actual = runner.query(sql);

        int cols = c.expected().get(0).size();
        persist(c.queryId(), "expected", c.expected(), cols);
        persist(c.queryId(), "actual", actual, cols);

        String q = c.queryId();
        var diff = runner.executor().execute(
                "SELECT count(*) AS n FROM ((SELECT * FROM expected_" + q + " EXCEPT ALL SELECT * FROM actual_" + q + ") "
                        + "UNION ALL "
                        + "(SELECT * FROM actual_" + q + " EXCEPT ALL SELECT * FROM expected_" + q + "))");
        long mismatches = ((Number) diff.rows().get(0).get(0)).longValue();
        if (mismatches > 0) {
            var onlyExpected = runner.executor().execute(
                    "SELECT * FROM (SELECT * FROM expected_" + q + " EXCEPT ALL SELECT * FROM actual_" + q + ") LIMIT 10");
            var onlyActual = runner.executor().execute(
                    "SELECT * FROM (SELECT * FROM actual_" + q + " EXCEPT ALL SELECT * FROM expected_" + q + ") LIMIT 10");
            assertTrue(false, queryId + ": " + mismatches + " mismatched rows. "
                    + "Only in expected: " + onlyExpected.rows() + " | only in actual: " + onlyActual.rows());
        }
        assertEquals(0L, mismatches, queryId + ": rows differ from Spark golden");
    }

    private static void persist(String queryId, String side, List<List<String>> rows, int cols) throws Exception {
        StringBuilder create = new StringBuilder("CREATE OR REPLACE TABLE " + side + "_" + queryId + " (");
        for (int i = 0; i < cols; i++) {
            if (i > 0) create.append(", ");
            create.append("c").append(i).append(" VARCHAR");
        }
        create.append(")");
        runner.executor().executeUpdate(create.toString());
        for (List<String> row : rows) {
            StringBuilder ins = new StringBuilder("INSERT INTO " + side + "_" + queryId + " VALUES (");
            for (int i = 0; i < cols; i++) {
                if (i > 0) ins.append(", ");
                String v = row.get(i);
                ins.append(v == null ? "NULL" : "'" + v.replace("'", "''") + "'");
            }
            ins.append(")");
            runner.executor().executeUpdate(ins.toString());
        }
    }

    private static String readQuery(String queryId) throws IOException {
        Path p = Path.of("src/test/resources/tpcds", queryId + ".sql");
        if (!Files.exists(p)) p = Path.of("localsql-thrift/src/test/resources/tpcds", queryId + ".sql");
        return Files.readString(p);
    }
}
