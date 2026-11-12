package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.analyzer.SemanticAnalyzer;
import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbSqlGenerator;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.statement.QueryStatement;
import io.github.joegxx.localsql.parser.StarRocksSqlParser;
import io.github.joegxx.localsql.rewrite.RewriteEngine;
import io.github.joegxx.localsql.starrocks.StarRocksAstBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * StarRocks 前端 L1 翻译回归：官方 TPC-DS 语料（docs/zh/benchmarking/
 * tpc_ds_99_sql.md，verbatim 拆分）经 StarRocks 前端跑完整流水线
 * parse -> IR -> analyze -> rewrite -> DuckDB SQL，不执行。
 * 与 Spark 侧 TpcdsQueryPipelineTest 对称。
 */
class StarRocksFrontendPipelineTest {

    private static final String RESOURCE_DIR = "tpcds-starrocks";

    /**
     * Queries exercising features the StarRocks front-end has not implemented
     * yet. Every entry must be a genuinely unimplemented feature, verified in
     * the failure message.
     */
    private static final List<String> KNOWN_GAPS = List.of();

    private static final StarRocksSqlParser parser = new StarRocksSqlParser();
    private static final StarRocksAstBuilder builder = new StarRocksAstBuilder();
    private static final DuckDbSqlGenerator generator = new DuckDbSqlGenerator();
    private static final RewriteEngine rewriter = new RewriteEngine(java.util.Set.of(), java.util.Set.of(), true);
    private static final SemanticAnalyzer analyzer = newAnalyzer();

    /**
     * Strict name resolution against the StarRocks function catalog: this
     * doubles as the catalog-coverage check — every function name in the
     * official corpus must bind, or the suite fails.
     */
    private static SemanticAnalyzer newAnalyzer() {
        try {
            CatalogService catalog = new CatalogService();
            TpcdsCatalogFixture.register(catalog);
            return new SemanticAnalyzer(catalog.catalog(),
                    io.github.joegxx.localsql.analyzer.StarRocksFunctionCatalog.instance(), true);
        } catch (Exception e) {
            throw new IllegalStateException("TPC-DS catalog fixture failed to register", e);
        }
    }

    static List<String> queryFiles() throws IOException {
        Path dir = resourceDir();
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }

    static Path resourceDir() {
        Path p = Path.of("src/test/resources", RESOURCE_DIR);
        if (Files.isDirectory(p)) return p;
        return Path.of("localsql-thrift/src/test/resources", RESOURCE_DIR);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryFiles")
    void translateTpcdsQuery(String fileName) throws IOException {
        assumeTrue(!KNOWN_GAPS.contains(fileName),
                "query uses a not-yet-implemented StarRocks front-end feature");

        String sql = Files.readString(resourceDir().resolve(fileName));

        Exception failure = null;
        try {
            var stmt = builder.buildStatement(sql, parser::parseStatement);
            assertTrue(stmt instanceof QueryStatement, fileName + ": not a query statement: " + stmt);
            Relation rel = ((QueryStatement) stmt).query();
            analyzer.analyze(rel);
            rewriter.rewrite(rel);
            String duckSql = generator.generate(rel);
            assertNotNull(duckSql, fileName + ": generate returned null");
            assertTrue(duckSql.length() > 0, fileName + ": generated SQL is empty");
        } catch (Exception e) {
            failure = e;
        }
        Exception err = failure;
        assertTrue(err == null,
                () -> fileName + " failed to translate: " + err.getClass().getSimpleName()
                        + ": " + err.getMessage());
    }

    @Test
    @DisplayName("StarRocks TPC-DS coverage matrix")
    void coverageMatrix() throws IOException {
        List<String> files = queryFiles();
        var statuses = new TreeMap<String, String>();
        for (String f : files) {
            statuses.put(f, KNOWN_GAPS.contains(f) ? "SKIP (known gap)" : "TRANSLATE");
        }
        System.out.println("StarRocks TPC-DS coverage: " + files.size() + " statements");
        int translated = (int) statuses.values().stream().filter("TRANSLATE"::equals).count();
        for (var e : statuses.entrySet()) System.out.printf("  %-14s %s%n", e.getKey(), e.getValue());
        System.out.printf("=> %d translate, %d known gaps%n", translated, files.size() - translated);
        assertTrue(translated >= files.size() - KNOWN_GAPS.size());
    }
}
