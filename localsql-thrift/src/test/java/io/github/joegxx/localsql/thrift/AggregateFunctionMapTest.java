package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.ir.type.IntegralType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Aggregate and statistical functions end-to-end over a scratch table.
 */
class AggregateFunctionMapTest {

    private DuckDbExecutor executor;
    private ThriftServer server;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        CatalogService catalog = new CatalogService(store);
        executor.registerTable("nums",
                List.of(new DuckDbExecutor.ColDef("x", "BIGINT"),
                        new DuckDbExecutor.ColDef("s", "VARCHAR")),
                List.of(List.of(1L, "a"), List.of(2L, "b"), List.of(3L, "a"), List.of(4L, "b")));
        catalog.registerTable("default", "nums", "TABLE",
                List.of(new CatalogService.ColumnDef("x", IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("s", new io.github.joegxx.localsql.ir.type.StringType(), true, null)),
                null, "duckdb", null, null, null);
        server = new ThriftServer(catalog, executor);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void percentileFamily() throws Exception {
        assertEquals("2.5", v("SELECT percentile(x, 0.5) FROM nums"));
        assertEquals("2.5", v("SELECT percentile_approx(x, 0.5) FROM nums"));
        assertEquals("2.5", v("SELECT approx_percentile(x, 0.5) FROM nums"));
    }

    @Test
    void aggregates() throws Exception {
        assertEquals("4", v("SELECT count(x) FROM nums"));
        assertEquals("10", v("SELECT sum(x) FROM nums"));
        assertEquals("2.5", v("SELECT avg(x) FROM nums"));
        assertEquals("2.5", v("SELECT median(x) FROM nums"));
        assertEquals("true", v("SELECT bool_and(x > 0) FROM nums"));
        assertEquals("true", v("SELECT every(x > 0) FROM nums"));
        assertEquals("true", v("SELECT any(x > 3) FROM nums"));
        assertEquals("4", v("SELECT approx_count_distinct(x) FROM nums"));
        assertEquals("2", v("SELECT count_if(x % 2 = 0) FROM nums"));
        assertEquals("[a,a,b,b]", normalize(v("SELECT collect_list(s) FROM nums")));
    }

    @Test
    void windowFunctions() throws Exception {
        assertEquals("1", v("SELECT row_number() OVER (ORDER BY x) FROM nums LIMIT 1"));
        assertEquals("4", v("SELECT rank() OVER (ORDER BY x DESC) FROM nums ORDER BY x LIMIT 1"));
        assertEquals("b", v("SELECT max_by(s, x) FROM nums"));
        assertEquals("a", v("SELECT min_by(s, x) FROM nums"));
    }

    private String v(String sql) throws Exception {
        Object o = server.executeSparkSql(sql).rows().get(0).get(0);
        return o == null ? "null" : String.valueOf(o);
    }

    private static String normalize(String s) {
        if (s.startsWith("[") && s.endsWith("]")) {
            String body = s.substring(1, s.length() - 1);
            return "[" + java.util.Arrays.stream(body.split(","))
                    .map(String::trim).sorted().reduce((a, b) -> a + "," + b).orElse("") + "]";
        }
        return s.replaceAll("\\s", "");
    }
}
