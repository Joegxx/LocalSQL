package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.udf.api.UdfGateway;
import io.github.joegxx.localsql.udf.api.UdfRegistry;

/**
 * Optional Java-UDF execution support for the SQL pipeline: the registry
 * decides which function calls become UDF boundaries, the gateway executes
 * them in batches, and timeoutMillis bounds each gateway call.
 */
public record UdfSupport(UdfRegistry registry, UdfGateway gateway, long timeoutMillis)
        implements AutoCloseable {

    public UdfSupport {
        if (registry == null) throw new IllegalArgumentException("UDF registry is required");
        if (gateway == null) throw new IllegalArgumentException("UDF gateway is required");
        if (timeoutMillis <= 0) timeoutMillis = 30_000;
    }

    @Override
    public void close() throws Exception {
        gateway.close();
    }
}
