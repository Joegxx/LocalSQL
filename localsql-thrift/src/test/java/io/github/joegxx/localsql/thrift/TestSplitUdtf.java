package io.github.joegxx.localsql.thrift;

import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDTF;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;

import java.util.List;

/** Test-only GenericUDTF: split(value, delim) forwards one row per token, NULL/empty produce nothing. */
public class TestSplitUdtf extends GenericUDTF {

    private StringObjectInspector value;
    private StringObjectInspector delim;

    @Override
    public StructObjectInspector initialize(ObjectInspector[] args) {
        this.value = (StringObjectInspector) args[0];
        this.delim = (StringObjectInspector) args[1];
        return ObjectInspectorFactory.getStandardStructObjectInspector(
                List.of("token"),
                List.of(PrimitiveObjectInspectorFactory.javaStringObjectInspector));
    }

    @Override
    public void process(Object[] args) throws HiveException {
        String v = value.getPrimitiveJavaObject(((GenericUDF.DeferredObject) args[0]).get());
        String d = delim.getPrimitiveJavaObject(((GenericUDF.DeferredObject) args[1]).get());
        if (v == null || d == null || d.isEmpty()) return;
        for (String token : v.split(java.util.regex.Pattern.quote(d))) {
            forward(token);
        }
    }

    @Override
    public void close() {}
}
