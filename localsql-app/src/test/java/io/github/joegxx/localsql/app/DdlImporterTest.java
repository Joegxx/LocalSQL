package io.github.joegxx.localsql.app;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.ir.type.DecimalType;
import io.github.joegxx.localsql.thrift.ThriftServer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlImporterTest {

    @Test
    void importsQualifiedAndBareDdlIdempotently() throws Exception {
        Path dir = Files.createTempDirectory("ddl");
        writeFile(dir.resolve("people.sql"), """
                -- header comment
                CREATE EXTERNAL TABLE db_alpha.people (
                  `id` bigint COMMENT 'primary key',
                  `name` varchar(10),
                  `score` decimal(19,4))
                COMMENT 'people table'
                ROW FORMAT SERDE 'org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe'
                STORED AS INPUTFORMAT 'org.apache.hadoop.mapred.TextInputFormat'
                OUTPUTFORMAT 'org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat'
                """);
        writeFile(dir.resolve("items.sql"), """
                CREATE TABLE items (
                  `item_id` int,
                  `title` string)
                """);

        try (DuckDbExecutor executor = new DuckDbExecutor()) {
            CatalogService catalog = new CatalogService(new DuckDbCatalogStore(executor));
            ThriftServer server = new ThriftServer(catalog, executor);

            DdlImporter.Stats s = DdlImporter.importAll(server, executor, dir, "mydb", null, null);
            assertEquals(2, s.total());
            assertEquals(2, s.ok());
            assertEquals(0, s.failed());

            assertTrue(catalog.getTable("db_alpha", "people").isPresent());
            assertTrue(catalog.getTable("mydb", "items").isPresent());

            CatalogService reloaded = new CatalogService(new DuckDbCatalogStore(executor));
            assertTrue(reloaded.catalog().getDatabase("db_alpha").isPresent());
            assertTrue(reloaded.catalog().getDatabase("mydb").isPresent());

            var people = catalog.getTable("db_alpha", "people").orElseThrow();
            assertEquals(3, people.columns().size());
            var scoreType = catalog.catalog().column(people, "score").type();
            assertTrue(scoreType instanceof DecimalType, "score type: " + scoreType);
            assertEquals(19, ((DecimalType) scoreType).precision());
            assertEquals(4, ((DecimalType) scoreType).scale());

            assertEquals(0, count(server, "SELECT count(*) AS n FROM db_alpha.people"));
            assertEquals(2, count(server, "INSERT INTO db_alpha.people VALUES (1, 'ann', 9.5), (2, 'bob', 8.75)"));
            assertEquals(2, count(server, "SELECT count(*) AS n FROM db_alpha.people"));
            assertEquals(2, count(server, "INSERT INTO mydb.items VALUES (1, 'a'), (2, 'b')"));
            assertEquals(2, count(server, "SELECT count(*) AS n FROM mydb.items"));

            // re-import is a no-op thanks to injected IF NOT EXISTS, data survives
            DdlImporter.Stats again = DdlImporter.importAll(server, executor, dir, "mydb", null, null);
            assertEquals(2, again.ok());
            assertEquals(0, again.failed());
            assertEquals(2, count(server, "SELECT count(*) AS n FROM mydb.items"));
        }
    }

    @Test
    void isolatesStatementFailures() throws Exception {
        Path dir = Files.createTempDirectory("ddl");
        writeFile(dir.resolve("good.sql"), "CREATE TABLE shop.goods (`id` int)");
        writeFile(dir.resolve("bad.sql"), "CREATE TABLE shop.bad (`x` int");

        try (DuckDbExecutor executor = new DuckDbExecutor()) {
            CatalogService catalog = new CatalogService(new DuckDbCatalogStore(executor));
            ThriftServer server = new ThriftServer(catalog, executor);

            DdlImporter.Stats s = DdlImporter.importAll(server, executor, dir, "default", null, null);
            assertEquals(2, s.total());
            assertEquals(1, s.ok());
            assertEquals(1, s.failed());
            assertTrue(catalog.getTable("shop", "goods").isPresent());
            assertTrue(catalog.getTable("shop", "bad").isEmpty());
        }
    }

    private static int count(ThriftServer server, String sql) throws Exception {
        Object v = server.executeSparkSql(sql).rows().get(0).get(0);
        return ((Number) v).intValue();
    }

    private static void writeFile(Path p, String content) throws Exception {
        Files.writeString(p, content);
    }
}
