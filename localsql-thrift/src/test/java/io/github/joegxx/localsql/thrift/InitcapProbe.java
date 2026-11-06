package io.github.joegxx.localsql.thrift;

import org.junit.jupiter.api.Test;

public class InitcapProbe {
    @Test
    public void probe() throws Exception {
        var executor = new io.github.joegxx.localsql.duckdb.DuckDbExecutor();
        var store = new io.github.joegxx.localsql.duckdb.DuckDbCatalogStore(executor);
        var catalog = new io.github.joegxx.localsql.catalog.CatalogService(store);
        var server = new ThriftServer(catalog, executor);
        var r = server.executeSparkSql("SELECT initcap('HELLO-WORLD foo')");
        System.out.println("PROBE_RESULT: " + r.rows().get(0).get(0));
        server.stop();
        executor.close();
    }
}
