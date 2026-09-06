package io.github.joegxx.localsql.udf.api;

public interface UdfGateway extends AutoCloseable {
    UdfBatch execute(UdfDefinition definition, UdfBatch input, long timeoutMillis) throws Exception;

    /** Executes a TABLE (GenericUDTF) definition: each input row may produce zero or more output rows. */
    default UdtfResult executeUdtf(UdfDefinition definition, UdfBatch input, long timeoutMillis) throws Exception {
        throw new UnsupportedOperationException(
                "Gateway does not support table-generating UDFs: " + getClass().getSimpleName());
    }

    @Override
    void close() throws Exception;
}
