package io.github.joegxx.localsql.app;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.ir.type.FractionalType;
import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.type.StringType;
import io.github.joegxx.localsql.thrift.ThriftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * CLI:
 *   runtime.jar [port]                                 in-memory demo (sample data)
 *   runtime.jar --load <file.duckdb> [port] [--schema <db>] [--udf-config <yaml>]
 *                                                      serve a persistent DuckDB file
 *   runtime.jar --import-ddl <dir> --out <file.duckdb> [--schema <db>]
 *               [--manifest <csv>] [--failures <txt>]  one-shot DDL import, then exit
 *
 * --udf-config points at a YAML registering Java Hive UDF jars:
 *   udfs:
 *     - name: mask_phone
 *       class: com.company.udf.MaskPhone
 *       jar: /opt/udf/company.jar
 *       kind: GENERIC
 *       args: [STRING]
 *       returns: STRING
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        String load = flag(args, "--load");
        String importDdl = flag(args, "--import-ddl");
        String out = flag(args, "--out", importDdl != null ? "localsql.duckdb" : null);
        String schema = flag(args, "--schema", "default");
        String manifest = flag(args, "--manifest");
        String failures = flag(args, "--failures", "import-failures.txt");
        String udfConfigPath = flag(args, "--udf-config");
        int port = port(args, 10000);

        LOG.info("LocalSQL Embedded Spark SQL Runtime starting...");

        io.github.joegxx.localsql.thrift.UdfSupport support = null;
        if (udfConfigPath != null) {
            var config = io.github.joegxx.localsql.udf.runtime.UdfConfigLoader.load(Paths.get(udfConfigPath));
            support = new io.github.joegxx.localsql.thrift.UdfSupport(
                    config.registry(), new io.github.joegxx.localsql.udf.runtime.HiveUdfGateway(),
                    config.timeoutMillis());
            LOG.info("Loaded {} UDF definitions from {} (timeout {}ms)",
                    config.registry().definitions().size(), udfConfigPath, config.timeoutMillis());
        }

        if (importDdl != null) {
            try (DuckDbExecutor executor = new DuckDbExecutor(out)) {
                CatalogService catalog = new CatalogService(new DuckDbCatalogStore(executor));
                ThriftServer server = new ThriftServer(catalog, executor);
                DdlImporter.importAll(server, executor, Paths.get(importDdl), schema,
                        manifest == null ? null : Paths.get(manifest), Paths.get(failures));
            }
            return;
        }

        if (load != null) {
            try (DuckDbExecutor executor = new DuckDbExecutor(load)) {
                CatalogService catalog = new CatalogService(new DuckDbCatalogStore(executor));
                if (!"default".equals(schema) && catalog.catalog().getDatabase(schema).isEmpty()) {
                    LOG.warn("--schema '{}' is not a database in {} (databases: {}); " +
                            "unqualified names will fail until USE switches the session",
                            schema, load, catalog.catalog().listDatabases());
                }
                ThriftServer server = new ThriftServer(catalog, executor, schema, support);
                server.start(port);
                LOG.info("Loaded {} from {} (default schema: {})",
                        tableCount(executor), load, server.defaultDatabase());
                holdUntilShutdown(server);
            }
            return;
        }

        try (DuckDbExecutor executor = new DuckDbExecutor()) {
            CatalogStore store = new DuckDbCatalogStore(executor);
            CatalogService catalog = new CatalogService(store);

            registerUsers(catalog, executor);
            registerOrders(catalog, executor);

            ThriftServer server = new ThriftServer(catalog, executor, schema, support);
            server.start(port);

            String demo = "SELECT u.name, count(*) AS cnt FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.name ORDER BY cnt DESC";
            LOG.info("Demo query: {}", demo);
            var result = server.executeSparkSql(demo);
            LOG.info("Result columns: {}", result.columns());
            for (var row : result.rows()) LOG.info("  {}", row);

            holdUntilShutdown(server);
        }
    }

    private static void holdUntilShutdown(ThriftServer server) throws InterruptedException {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down...");
            server.stop();
        }));
        Thread.currentThread().join();
    }

    private static long tableCount(DuckDbExecutor executor) {
        try {
            return ((Number) executor.execute("SELECT count(*) FROM catalog_table")
                    .rows().get(0).get(0)).longValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String flag(String[] args, String name) {
        return flag(args, name, null);
    }

    private static String flag(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) return args[i + 1];
        }
        return fallback;
    }

    private static int port(String[] args, int fallback) {
        for (String a : args) {
            try { return Integer.parseInt(a); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static void registerUsers(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        String duckTable = "users";
        var colDefs = List.of(
                new DuckDbExecutor.ColDef("id", "BIGINT"),
                new DuckDbExecutor.ColDef("name", "VARCHAR"),
                new DuckDbExecutor.ColDef("age", "INT"));
        var rows = List.<List<Object>>of(
                List.of(1L, "alice", 30),
                List.of(2L, "bob", 25),
                List.of(3L, "carol", 40));
        executor.registerTable(duckTable, colDefs, rows);
        String createSql = "CREATE TABLE users (id BIGINT, name VARCHAR, age INT)";
        var table = catalog.registerTable("default", "users", "TABLE",
                List.of(
                        new CatalogService.ColumnDef("id", IntegralType.BIGINT, false, null),
                        new CatalogService.ColumnDef("name", new StringType(), true, null),
                        new CatalogService.ColumnDef("age", IntegralType.INT, true, null)),
                null, "duckdb", "users table", Map.of(), null);
        catalog.store().saveRuntimeInfo(table.tableId(), duckTable, createSql);
    }

    private static void registerOrders(CatalogService catalog, DuckDbExecutor executor) throws Exception {
        String duckTable = "orders";
        var colDefs = List.of(
                new DuckDbExecutor.ColDef("id", "BIGINT"),
                new DuckDbExecutor.ColDef("user_id", "BIGINT"),
                new DuckDbExecutor.ColDef("amount", "DOUBLE"));
        var rows = List.<List<Object>>of(
                List.of(100L, 1L, 99.5),
                List.of(101L, 1L, 20.0),
                List.of(102L, 2L, 150.0));
        executor.registerTable(duckTable, colDefs, rows);
        String createSql = "CREATE TABLE orders (id BIGINT, user_id BIGINT, amount DOUBLE)";
        var table = catalog.registerTable("default", "orders", "TABLE",
                List.of(
                        new CatalogService.ColumnDef("id", IntegralType.BIGINT, false, null),
                        new CatalogService.ColumnDef("user_id", IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("amount", FractionalType.DOUBLE, true, null)),
                null, "duckdb", "orders table", Map.of(), null);
        catalog.store().saveRuntimeInfo(table.tableId(), duckTable, createSql);
    }
}
