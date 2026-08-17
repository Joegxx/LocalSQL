package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.analyzer.SemanticAnalyzer;
import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbSqlGenerator;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.parser.SparkSqlParser;
import io.github.joegxx.localsql.rewrite.RewriteEngine;
import io.github.joegxx.localsql.spark.SparkAstBuilder;
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
 * Unified TPC-DS pipeline test. Each query from tpcds-v2.7.0 resources is run
 * through the full local pipeline: parse -> IR -> analyze -> rewrite -> DuckDB SQL.
 *
 * Queries whose SQL features are not yet supported by the MVP are listed in
 * UNSUPPORTED and reported as skipped; every other query must translate
 * successfully. Add entries here only for genuinely unimplemented features.
 */
class TpcdsQueryPipelineTest {

    /** Queries that exercise features the MVP has not implemented yet. */
    private static final List<String> UNSUPPORTED = List.of(
            // ROLLUP grouping analytics (q22)
            "q22.sql"
    );

    private static final SparkSqlParser parser = new SparkSqlParser();
    private static final SparkAstBuilder builder = new SparkAstBuilder();
    private static final DuckDbSqlGenerator generator = new DuckDbSqlGenerator();
    private static final RewriteEngine rewriter = new RewriteEngine();
    private static final SemanticAnalyzer analyzer =
            new SemanticAnalyzer(new CatalogService().catalog());

    static List<String> queryFiles() throws IOException {
        Path dir = Path.of("src/test/resources/tpcds-v2.7.0");
        if (!Files.isDirectory(dir)) {
            dir = Path.of("localsql-thrift/src/test/resources/tpcds-v2.7.0");
        }
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("q") && n.endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryFiles")
    void translateTpcdsQuery(String fileName) throws IOException {
        assumeTrue(!UNSUPPORTED.contains(fileName),
                "query uses an MVP-unsupported feature");

        Path dir = Path.of("src/test/resources/tpcds-v2.7.0");
        if (!Files.isDirectory(dir)) {
            dir = Path.of("localsql-thrift/src/test/resources/tpcds-v2.7.0");
        }
        String sql = Files.readString(dir.resolve(fileName));

        Exception failure = null;
        try {
            var ctx = parser.parseStatement(sql);
            Relation rel = builder.buildStatement(sql, s -> parser.parseStatement(s));
            assertNotNull(rel, fileName + ": buildStatement returned null");
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

    /** Print a coverage matrix for all queries (supported vs. unsupported). */
    @Test
    @DisplayName("TPC-DS coverage matrix")
    void coverageMatrix() throws IOException {
        List<String> files = queryFiles();
        var statuses = new TreeMap<String, String>();
        for (String f : files) {
            statuses.put(f, UNSUPPORTED.contains(f) ? "SKIP (unsupported feature)" : "TRANSLATE");
        }
        System.out.println("TPC-DS coverage: " + files.size() + " queries");
        int translated = (int) statuses.values().stream().filter("TRANSLATE"::equals).count();
        int skipped = (int) statuses.values().stream().filter(s -> s.startsWith("SKIP")).count();
        for (var e : statuses.entrySet()) System.out.printf("  %-12s %s%n", e.getKey(), e.getValue());
        System.out.printf("=> %d translate, %d unsupported%n", translated, skipped);
        assertTrue(translated >= files.size() - UNSUPPORTED.size());
    }
}
