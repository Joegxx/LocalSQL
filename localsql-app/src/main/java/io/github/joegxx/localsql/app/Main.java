package io.github.joegxx.localsql.app;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.thrift.ThriftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        int port = 10000;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        LOG.info("LocalSQL Embedded Spark SQL Runtime starting...");
        CatalogService catalog = new CatalogService();
        catalog.registerSampleTable("users", "id", "name", "age");
        catalog.registerSampleTable("orders", "id", "user_id", "amount");

        try (DuckDbExecutor executor = new DuckDbExecutor()) {
            executor.registerSampleTable("users", List.of("id", "name", "age"), List.of(
                    List.of("1", "alice", "30"),
                    List.of("2", "bob", "25"),
                    List.of("3", "carol", "40")
            ));
            executor.registerSampleTable("orders", List.of("id", "user_id", "amount"), List.of(
                    List.of("100", "1", "99.5"),
                    List.of("101", "1", "20.0"),
                    List.of("102", "2", "150.0")
            ));

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
}
