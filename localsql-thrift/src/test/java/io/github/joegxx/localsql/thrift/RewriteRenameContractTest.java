package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.analyzer.BuiltInFunctionCatalog;
import io.github.joegxx.localsql.analyzer.SemanticAnalyzer;
import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.duckdb.DuckDbSqlGenerator;
import io.github.joegxx.localsql.ir.expression.FunctionCall;
import io.github.joegxx.localsql.ir.relation.Project;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.parser.SparkSqlParser;
import io.github.joegxx.localsql.rewrite.RewriteEngine;
import io.github.joegxx.localsql.spark.SparkAstBuilder;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 milestone contract: rename-family functions are rewritten to their
 * target IR before generation; the generator's FN_MAP no longer owns them
 * and emits the already-rewritten name verbatim. Reserved names (custom
 * function-map rules, UDFs) keep their single conversion ownership (A1.3).
 */
class RewriteRenameContractTest {

    private final SparkAstBuilder astBuilder = new SparkAstBuilder();
    private final SparkSqlParser parser = new SparkSqlParser();

    private CatalogService catalog() throws Exception {
        DuckDbExecutor executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        CatalogService catalog = new CatalogService(store);
        catalog.registerTable("default", "t", "TABLE",
                java.util.List.of(new CatalogService.ColumnDef("a",
                        new io.github.joegxx.localsql.ir.type.StringType(), true, null)),
                null, "duckdb", null, null, null);
        executor.run("CREATE TABLE t (a VARCHAR)");
        return catalog;
    }

    private Relation analyze(String sql) throws Exception {
        CatalogService catalog = catalog();
        var stmt = new SemanticAnalyzer(catalog.catalog(), BuiltInFunctionCatalog.instance(), false)
                .analyze(astBuilder.buildStatement(sql, s -> parser.parseStatement(s)));
        return ((io.github.joegxx.localsql.ir.statement.QueryStatement) stmt).query();
    }

    private Relation rewrite(Relation rel) throws Exception {
        try (DuckDbExecutor executor = new DuckDbExecutor()) {
            CatalogStore store = new DuckDbCatalogStore(executor);
            new RewriteEngine(new DuckDbSqlGenerator().customFunctionNames())
                    .rewrite(rel);
        }
        return rel;
    }

    private String generate(Relation rel) {
        return new DuckDbSqlGenerator().generate(rel);
    }

    @Test
    void upperCaseRenameHappensInRewriteNotGenerator() throws Exception {
        // upper/lower are identity entries: rewrite must not change them, and
        // the generator must emit them verbatim
        Relation rel = analyze("SELECT size(a) FROM t");
        rewrite(rel);
        FunctionCall call = firstCall(rel);
        assertEquals("array_length", call.name());
        String sql = generate(rel);
        assertTrue(sql.contains("array_length("), sql);
    }

    @Test
    void instrBecomesStrposBeforeGeneration() throws Exception {
        Relation rel = analyze("SELECT instr(a, 'x') FROM t");
        rewrite(rel);
        assertEquals("strpos", firstCall(rel).name());
        String sql = generate(rel);
        assertTrue(sql.contains("strpos("), sql);
        assertTrue(!sql.contains("instr("), sql);
    }

    @Test
    void generatorNoLongerMapsRenamedFunctions() throws Exception {
        // without rewrite the raw source name flows through untouched (the
        // generator is a pure serializer and has no mapping for it)
        Relation rel = analyze("SELECT instr(a, 'x') FROM t");
        String sql = generate(rel);
        assertTrue(sql.contains("instr("), sql);
    }

    @Test
    void reservedNamesAreNotRenamed() throws Exception {
        // a custom rename for a built-in keeps the generator-owned rule: the
        // rewrite pass skips it and the custom rename applies at emit time
        DuckDbSqlGenerator custom = new DuckDbSqlGenerator(
                java.util.Map.of("instr", "my_instr"), java.util.Map.of());
        Relation rel = analyze("SELECT instr(a, 'x') FROM t");
        new RewriteEngine(custom.customFunctionNames()).rewrite(rel);
        assertEquals("instr", firstCall(rel).name());
        String sql = custom.generate(rel);
        assertTrue(sql.contains("my_instr("), sql);
    }

    @Test
    void nestedCallsAllRewrite() throws Exception {
        Relation rel = analyze("SELECT size(split(a, ',')) FROM t");
        rewrite(rel);
        // split -> regexp_split_to_array, size -> array_length
        String sql = generate(rel);
        assertTrue(sql.contains("array_length("), sql);
        assertTrue(sql.contains("regexp_split_to_array("), sql);
    }

    private FunctionCall firstCall(Relation rel) {
        if (rel instanceof Project p && p.projectList().get(0) instanceof FunctionCall f) {
            return f;
        }
        throw new IllegalStateException("expected Project(FunctionCall), got: " + rel);
    }
}
