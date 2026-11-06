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

    @Test
    void preprocessKeepsIdentifiersContainingCharOrVarchar() throws Exception {
        Path dir = Files.createTempDirectory("ddl");
        writeFile(dir.resolve("fees.sql"), """
                CREATE TABLE db_fees.fee_charge (
                  `charge_code` varchar(20),
                  `character_note` string,
                  `charge_total` double,
                  `note` string COMMENT 'charge detail')
                """);

        try (DuckDbExecutor executor = new DuckDbExecutor()) {
            CatalogService catalog = new CatalogService(new DuckDbCatalogStore(executor));
            ThriftServer server = new ThriftServer(catalog, executor);

            DdlImporter.Stats s = DdlImporter.importAll(server, executor, dir, "default", null, null);
            assertEquals(1, s.ok(), "failures: " + s.failures());
            assertEquals(0, s.failed());

            var feeCharge = catalog.getTable("db_fees", "fee_charge").orElseThrow();
            assertEquals(4, feeCharge.columns().size());
            var chargeCode = catalog.catalog().column(feeCharge, "charge_code");
            var chargeTotal = catalog.catalog().column(feeCharge, "charge_total");
            var characterNote = catalog.catalog().column(feeCharge, "character_note");
            assertTrue(chargeCode.type() instanceof io.github.joegxx.localsql.ir.type.StringType,
                    "charge_code should be string, got: " + chargeCode.type());
            assertTrue(chargeTotal.type() instanceof io.github.joegxx.localsql.ir.type.FractionalType,
                    "charge_total should keep double, got: " + chargeTotal.type());
            assertEquals("character_note", characterNote.name(),
                    "identifier containing 'char' must survive preprocessing");
        }
    }

    @Test
    void failureCleanupKeepsPreExistingPhysicalTableOfOtherDatabase() throws Exception {
        Path dir = Files.createTempDirectory("ddl");
        // both failing entries share the bare physical name "shared" with a
        // pre-existing table of another logical database
        writeFile(dir.resolve("badA.sql"), "CREATE TABLE db_a.shared (`x` int");
        writeFile(dir.resolve("badC.sql"), "CREATE TABLE db_c.shared (`z` int");

        try (DuckDbExecutor executor = new DuckDbExecutor()) {
            CatalogService catalog = new CatalogService(new DuckDbCatalogStore(executor));
            ThriftServer server = new ThriftServer(catalog, executor);
            server.executeSparkSql("CREATE DATABASE db_b");
            // pre-existing logical+physical table with the same bare name
            server.executeSparkSql("CREATE TABLE db_b.shared (`y` int)");

            DdlImporter.importAll(server, executor, dir, "default", null, null);

            // failed entries must not have deleted the pre-existing table
            assertTrue(catalog.getTable("db_b", "shared").isPresent(),
                    "pre-existing db_b.shared must survive failed imports");
            var rows = server.executeSparkSql("SELECT count(*) AS n FROM db_b.shared").rows();
            assertEquals(1, rows.size(), "physical table must stay queryable");
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
