package io.github.joegxx.localsql.udf.runtime;

import org.apache.hadoop.hive.ql.exec.UDF;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;

/** Test-only simple UDF with evaluate overloads: Text, (String, Integer), LongWritable. */
public class TestSimpleUdf extends UDF {

    public Text evaluate(Text s) {
        return s == null ? null : new Text(s.toString().toUpperCase());
    }

    public String evaluate(String s, Integer n) {
        return s == null ? null : s + n;
    }

    public LongWritable evaluate(LongWritable v) {
        return v == null ? null : new LongWritable(v.get() * 2);
    }
}
