package io.github.joegxx.localsql.thrift;

import org.apache.hadoop.hive.ql.udf.generic.GenericUDF;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF.DeferredObject;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;

/** Test-only GenericUDF: uppercase + "!" suffix, NULL propagates. */
public class TestUpperUdf extends GenericUDF {

    private StringObjectInspector input;

    @Override
    public ObjectInspector initialize(ObjectInspector[] arguments) {
        this.input = (StringObjectInspector) arguments[0];
        return PrimitiveObjectInspectorFactory.javaStringObjectInspector;
    }

    @Override
    public Object evaluate(DeferredObject[] arguments) throws org.apache.hadoop.hive.ql.metadata.HiveException {
        String v = input.getPrimitiveJavaObject(arguments[0].get());
        return v == null ? null : v.toUpperCase() + "!";
    }

    @Override
    public String getDisplayString(String[] children) {
        return "test_upper(" + children[0] + ")";
    }
}
