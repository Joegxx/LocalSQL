package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.udf.api.UdfBatch;
import io.github.joegxx.localsql.udf.api.UdfDefinition;
import io.github.joegxx.localsql.udf.api.UdfGateway;
import io.github.joegxx.localsql.udf.api.UdtfResult;

/**
 * Dispatches registered UDFs by kind: GENERIC to the GenericUDF executor,
 * TABLE to the GenericUDTF executor.
 */
public final class HiveUdfGateway implements UdfGateway {

    private final HiveGenericUdfGateway generic = new HiveGenericUdfGateway();
    private final HiveGenericUdtfGateway udtf = new HiveGenericUdtfGateway();

    @Override
    public UdfBatch execute(UdfDefinition definition, UdfBatch input, long timeoutMillis) throws Exception {
        if (definition.kind() != io.github.joegxx.localsql.udf.api.UdfKind.GENERIC) {
            throw new IllegalArgumentException("Function " + definition.id() + " has kind " + definition.kind()
                    + "; only GENERIC functions may appear in scalar expressions");
        }
        return generic.execute(definition, input, timeoutMillis);
    }

    @Override
    public UdtfResult executeUdtf(UdfDefinition definition, UdfBatch input, long timeoutMillis) throws Exception {
        if (definition.kind() != io.github.joegxx.localsql.udf.api.UdfKind.TABLE) {
            throw new IllegalArgumentException("Function " + definition.id() + " has kind " + definition.kind()
                    + "; LATERAL VIEW requires kind TABLE");
        }
        return udtf.executeUdtf(definition, input, timeoutMillis);
    }

    @Override
    public void close() throws Exception {
        generic.close();
        udtf.close();
    }
}
