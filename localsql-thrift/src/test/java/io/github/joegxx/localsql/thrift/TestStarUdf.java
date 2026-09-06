package io.github.joegxx.localsql.thrift;

import org.apache.hadoop.hive.ql.exec.UDF;

/** Test-only simple UDF (exec.UDF): masks any string with "***". */
public class TestStarUdf extends UDF {

    public String evaluate(String s) {
        return s == null ? null : "***";
    }
}
