package io.github.joegxx.localsql.udf.runtime;

import org.apache.hadoop.hive.ql.udf.generic.GenericUDF;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF.DeferredObject;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;

/** Test-only GenericUDF: string suffix concat with NULL propagation. */
public class TestSuffixUdf extends GenericUDF {

    private StringObjectInspector left;
    private StringObjectInspector right;

    @Override
    public ObjectInspector initialize(ObjectInspector[] arguments) {
        this.left = (StringObjectInspector) arguments[0];
        this.right = (StringObjectInspector) arguments[1];
        return PrimitiveObjectInspectorFactory.javaStringObjectInspector;
    }

    @Override
    public Object evaluate(DeferredObject[] arguments) throws org.apache.hadoop.hive.ql.metadata.HiveException {
        String a = left.getPrimitiveJavaObject(arguments[0].get());
        String b = right.getPrimitiveJavaObject(arguments[1].get());
        if (a == null) return null;
        return a + b;
    }

    @Override
    public String getDisplayString(String[] children) {
        return "suffix(" + children[0] + ", " + children[1] + ")";
    }
}
