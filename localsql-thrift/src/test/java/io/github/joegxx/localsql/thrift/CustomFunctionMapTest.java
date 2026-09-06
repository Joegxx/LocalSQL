package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.duckdb.DuckDbSqlGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end for --function-map: user-configured renames and templates
 * override built-in function emission.
 */
class CustomFunctionMapTest {

    private ThriftServer server;

    @BeforeEach
    void setUp() throws Exception {
        DuckDbExecutor executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        CatalogService catalog = new CatalogService(store);
        DuckDbSqlGenerator generator = new DuckDbSqlGenerator(
                Map.of("my_upper", "upper"),
                Map.of("next_friday", "date_add({0}, 7)"));
        server = new ThriftServer(catalog, executor, "default", null, generator);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void customRenameOverridesFunctionName() throws Exception {
        var r = server.executeSparkSql("SELECT my_upper('ab')");
        assertEquals("AB", r.rows().get(0).get(0));
    }

    @Test
    void customTemplateRewritesArguments() throws Exception {
        var r = server.executeSparkSql("SELECT next_friday(date '2020-03-05')");
        Object v = r.rows().get(0).get(0);
        String s = String.valueOf(v);
        if (s.length() > 10) s = s.substring(0, 10);
        assertEquals("2020-03-12", s);
    }

    @Test
    void builtinsStillWorkAlongsideCustomMap() throws Exception {
        var r = server.executeSparkSql("SELECT nvl(null, 'x'), upper('ab')");
        assertEquals("x", r.rows().get(0).get(0));
        assertEquals("AB", r.rows().get(0).get(1));
    }
}
