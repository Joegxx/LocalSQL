package io.github.joegxx.localsql.app;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.type.StringType;
import io.github.joegxx.localsql.ir.type.FractionalType;
import io.github.joegxx.localsql.thrift.ThriftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        int port = 10000;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        LOG.info("LocalSQL Embedded Spark SQL Runtime starting...");

        try (DuckDbExecutor executor = new DuckDbExecutor()) {
            CatalogStore store = new DuckDbCatalogStore(executor);
            CatalogService catalog = new CatalogService(store);

            registerUsers(catalog, executor);
            registerOrders(catalog, executor);

            ThriftServer server = new ThriftServer(catalog, executor);
            server.start(port);

            String demo = "SELECT u.name, count(*) AS cnt FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.name ORDER BY cnt DESC";
            LOG.info("Demo query: {}", demo);
            var result = server.executeSparkSql(demo);
            LOG.info("Result columns: {}", result.columns());
            for (var row : result.rows()) LOG.info("  {}", row);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutting down...");
                server.stop();
            }));
            Thread.currentThread().join();
        }
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
