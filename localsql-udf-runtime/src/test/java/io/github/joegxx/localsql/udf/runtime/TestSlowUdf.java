package io.github.joegxx.localsql.udf.runtime;

import org.apache.hadoop.hive.ql.udf.generic.GenericUDF;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF.DeferredObject;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.LongObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;

/** Test-only GenericUDF that sleeps per row, for timeout behavior. */
public class TestSlowUdf extends GenericUDF {

    private LongObjectInspector input;

    @Override
    public ObjectInspector initialize(ObjectInspector[] arguments) {
        this.input = (LongObjectInspector) arguments[0];
        return PrimitiveObjectInspectorFactory.javaLongObjectInspector;
    }

    @Override
    public Object evaluate(DeferredObject[] arguments) throws org.apache.hadoop.hive.ql.metadata.HiveException {
        Object raw = arguments[0].get();
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return raw == null ? null : ((Number) raw).longValue() + 1;
    }

    @Override
    public String getDisplayString(String[] children) {
        return "slow(" + children[0] + ")";
    }
}
