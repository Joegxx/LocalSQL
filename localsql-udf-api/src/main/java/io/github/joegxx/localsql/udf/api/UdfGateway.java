package io.github.joegxx.localsql.udf.api;

public interface UdfGateway extends AutoCloseable {
    UdfBatch execute(UdfDefinition definition, UdfBatch input, long timeoutMillis) throws Exception;

    @Override
    void close() throws Exception;
}
