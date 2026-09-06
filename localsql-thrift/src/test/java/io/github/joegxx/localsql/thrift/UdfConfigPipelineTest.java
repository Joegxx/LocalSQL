package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.ir.type.StringType;
import io.github.joegxx.localsql.udf.api.UdfDefinition;
import io.github.joegxx.localsql.udf.api.UdfKind;
import io.github.joegxx.localsql.udf.api.UdfRegistry;
import io.github.joegxx.localsql.udf.runtime.HiveUdfGateway;
import io.github.joegxx.localsql.udf.runtime.UdfConfigLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end through the YAML config path: UdfConfigLoader -> registry ->
 * composite HiveUdfGateway -> GENERIC projection split and TABLE UDTF
 * expansion, exactly like runtime.jar --udf-config wires it.
 */
class UdfConfigPipelineTest {

    private DuckDbExecutor executor;
    private CatalogService catalog;
    private ThriftServer server;
    private UdfSupport support;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        catalog = new CatalogService(store);
        executor.registerTable("people",
                List.of(new DuckDbExecutor.ColDef("id", "BIGINT"),
                        new DuckDbExecutor.ColDef("name", "VARCHAR"),
                        new DuckDbExecutor.ColDef("tags", "VARCHAR")),
                List.of(List.of(1L, "alice", "x,y"),
                        List.of(2L, "bob", "z")));
        catalog.registerTable("default", "people", "TABLE",
                List.of(new CatalogService.ColumnDef("id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("name", new StringType(), true, null),
                        new CatalogService.ColumnDef("tags", new StringType(), true, null)),
                null, "duckdb", null, null, null);

        Path testClasses = Path.of(System.getProperty("user.dir"), "target", "test-classes");
        Path yaml = Files.createTempFile("udfs", ".yaml");
        Files.writeString(yaml, """
                timeoutMillis: 15000
                udfs:
                  - name: test_upper
                    class: io.github.joegxx.localsql.thrift.TestUpperUdf
                    jar: %s
                    kind: GENERIC
                    args: [STRING]
                    returns: STRING
                  - name: test_split
                    class: io.github.joegxx.localsql.thrift.TestSplitUdtf
                    jar: %s
                    kind: TABLE
                    args: [STRING, STRING]
                  - name: test_star
                    class: io.github.joegxx.localsql.thrift.TestStarUdf
                    jar: %s
                    kind: SCALAR
                    args: [STRING]
                    returns: STRING
                """.formatted(testClasses, testClasses, testClasses));

        UdfConfigLoader.UdfConfig config = UdfConfigLoader.load(yaml);
        support = new UdfSupport(config.registry(), new HiveUdfGateway(), config.timeoutMillis());
        server = new ThriftServer(catalog, executor, "default", support);
    }

    @AfterEach
    void tearDown() throws Exception {
        support.close();
    }

    @Test
    void yamlRegisteredGenericUdfRunsInProjection() throws Exception {
        var r = server.executeSparkSql(
                "SELECT id, test_upper(name) AS up FROM people ORDER BY id");
        assertEquals(2, r.rows().size());
        assertEquals("ALICE!", r.rows().get(0).get(1));
        assertEquals("BOB!", r.rows().get(1).get(1));
    }

    @Test
    void yamlRegisteredTableUdfExpandsLateralView() throws Exception {
        var r = server.executeSparkSql(
                "SELECT id, tok FROM people LATERAL VIEW test_split(tags, ',') t AS tok ORDER BY id, tok");
        assertEquals(3, r.rows().size());
        assertEquals("x", r.rows().get(0).get(1));
        assertEquals("y", r.rows().get(1).get(1));
        assertEquals("z", r.rows().get(2).get(1));
    }

    @Test
    void yamlRegisteredSimpleUdfRunsInProjection() throws Exception {
        var r = server.executeSparkSql(
                "SELECT id, test_star(name) AS starred FROM people ORDER BY id");
        assertEquals(2, r.rows().size());
        assertEquals("***", r.rows().get(0).get(1));
        assertEquals("***", r.rows().get(1).get(1));
    }

    @Test
    void unregisteredFunctionStillGoesToDuckDB() {
        SQLException e = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> server.executeSparkSql("SELECT nosuch_fn(name) FROM people"));
        assertTrue(String.valueOf(e.getMessage()).contains("nosuch_fn"),
                () -> "unexpected: " + e.getMessage());
    }
}
