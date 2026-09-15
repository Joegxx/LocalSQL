package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.analyzer.BuiltInFunctionCatalog;
import io.github.joegxx.localsql.analyzer.SemanticAnalyzer;
import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.spark.SparkAstBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3.6 gate behavior: with strict function checking the analyzer rejects
 * calls to functions unknown to the catalog before execution. The lenient
 * mode (pipeline default in this milestone) passes unknown names through
 * so native passthrough keeps working until A3 migration completes.
 */
class SemanticAnalyzerContractTest {

    private final SparkAstBuilder astBuilder = new SparkAstBuilder();

    private record Env(DuckDbExecutor executor, CatalogService catalog) {}

    private Env env() throws Exception {
        DuckDbExecutor executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        CatalogService catalog = new CatalogService(store);
        catalog.registerTable("default", "users", "TABLE",
                java.util.List.of(new CatalogService.ColumnDef("id",
                        io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null)),
                null, "duckdb", null, null, null);
        executor.run("CREATE TABLE users (id BIGINT)");
        return new Env(executor, catalog);
    }

    private SemanticAnalyzer analyzer(boolean strict) throws Exception {
        Env e = env();
        return new SemanticAnalyzer(e.catalog().catalog(), BuiltInFunctionCatalog.instance(), strict);
    }

    @Test
    void strictModeRejectsUnknownFunction() throws Exception {
        var rel = astBuilder.buildStatement("SELECT no_such_fn(id) FROM users",
                s -> new io.github.joegxx.localsql.parser.SparkSqlParser().parseStatement(s));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> analyzer(true).analyze(rel));
        assertTrue(e.getMessage().contains("no_such_fn"), () -> e.getMessage());
    }

    @Test
    void lenientModeAllowsUnknownFunction() throws Exception {
        var rel = astBuilder.buildStatement("SELECT no_such_fn(id) FROM users",
                s -> new io.github.joegxx.localsql.parser.SparkSqlParser().parseStatement(s));
        // passes through (table t is unknown too, which is allowed at this stage)
        var analyzed = analyzer(false).analyze(rel);
        assertTrue(analyzed != null);
    }

    @Test
    void strictModeStillAcceptsKnownFunctions() throws Exception {
        Env e = env();
        var rel = astBuilder.buildStatement("SELECT upper(nm) FROM (SELECT CAST(id AS STRING) AS nm FROM users)",
                s -> new io.github.joegxx.localsql.parser.SparkSqlParser().parseStatement(s));
        var analyzed = new SemanticAnalyzer(e.catalog().catalog(), BuiltInFunctionCatalog.instance(), true).analyze(rel);
        assertTrue(analyzed != null);
    }

    @Test
    void knownFunctionWithValidArityPassesStrictMode() throws Exception {
        Env e = env();
        var rel = astBuilder.buildStatement("SELECT concat(CAST(id AS STRING), 'x') FROM users",
                s -> new io.github.joegxx.localsql.parser.SparkSqlParser().parseStatement(s));
        var analyzed = new SemanticAnalyzer(e.catalog().catalog(), BuiltInFunctionCatalog.instance(), true)
                .analyze(rel);
        assertTrue(analyzed != null);
    }
}
