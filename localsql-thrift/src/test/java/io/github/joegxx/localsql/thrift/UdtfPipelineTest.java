package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.ir.type.StringType;
import io.github.joegxx.localsql.udf.api.UdfDefinition;
import io.github.joegxx.localsql.udf.api.UdfKind;
import io.github.joegxx.localsql.udf.api.UdfRegistry;
import io.github.joegxx.localsql.udf.runtime.HiveGenericUdtfGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: a registered GenericUDTF expands LATERAL VIEW rows through the
 * gateway into a DuckDB temp table; outer filters/ORDER BY/LIMIT keep normal
 * semantics against the expanded result.
 */
class UdtfPipelineTest {

    private DuckDbExecutor executor;
    private CatalogService catalog;
    private ThriftServer server;
    private UdfSupport support;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        catalog = new CatalogService(store);
        executor.registerTable("svt",
                List.of(new DuckDbExecutor.ColDef("id", "BIGINT"),
                        new DuckDbExecutor.ColDef("tags", "VARCHAR")),
                List.of(List.of(1L, "a,b"), java.util.Arrays.asList(2L, null), List.of(3L, "c")));
        catalog.registerTable("default", "svt", "TABLE",
                List.of(new CatalogService.ColumnDef("id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("tags", new StringType(), true, null)),
                null, "duckdb", null, null, null);

        UdfRegistry registry = new UdfRegistry();
        Path testClasses = Path.of(System.getProperty("user.dir"), "target", "test-classes");
        registry.register(new UdfDefinition("test.split", "test_split", TestSplitUdtf.class.getName(),
                UdfKind.TABLE, List.of(new StringType(), new StringType()), new StringType(),
                testClasses.toString(), "3.1", 0));
        support = new UdfSupport(registry, new HiveGenericUdtfGateway(), 10_000);
        server = new ThriftServer(catalog, executor, "default", support);
    }

    @AfterEach
    void tearDown() throws Exception {
        support.close();
    }

    @Test
    void expandsRowsAndKeepsOuterOrderBy() throws Exception {
        var r = server.executeSparkSql(
                "SELECT id, tok FROM svt LATERAL VIEW test_split(tags, ',') t2 AS tok ORDER BY id, tok");
        assertEquals(List.of("id", "tok"), r.columns());
        assertEquals(3, r.rows().size());
        assertEquals(1L, r.rows().get(0).get(0));
        assertEquals("a", r.rows().get(0).get(1));
        assertEquals("b", r.rows().get(1).get(1));
        assertEquals("c", r.rows().get(2).get(1));
    }

    @Test
    void outerFilterAppliesAfterExpansion() throws Exception {
        var r = server.executeSparkSql(
                "SELECT id, tok FROM svt LATERAL VIEW test_split(tags, ',') t2 AS tok WHERE tok = 'a' ORDER BY id");
        assertEquals(1, r.rows().size());
        assertEquals(1L, r.rows().get(0).get(0));
        assertEquals("a", r.rows().get(0).get(1));
    }

    @Test
    void nullInputExpandsToZeroRows() throws Exception {
        var r = server.executeSparkSql(
                "SELECT id, tok FROM svt LATERAL VIEW test_split(tags, ',') t2 AS tok WHERE id = 2");
        assertTrue(r.rows().isEmpty());
    }

    @Test
    void limitOnExpandedRows() throws Exception {
        var r = server.executeSparkSql(
                "SELECT tok FROM svt LATERAL VIEW test_split(tags, ',') t2 AS tok LIMIT 2");
        assertEquals(2, r.rows().size());
    }

    @Test
    void rejectsQualifiedOuterReferences() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> server.executeSparkSql(
                        "SELECT t2.tok FROM svt LATERAL VIEW test_split(tags, ',') t2 AS tok"));
        assertTrue(e.getMessage().contains("Qualified references"), () -> "unexpected: " + e.getMessage());
    }
}
