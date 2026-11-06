package io.github.joegxx.localsql.rewrite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Schema-pattern translation moved out of the generator (A1). */
class SparkDialectFormatsTest {

    @Test
    void parsesNestedSchemas() {
        assertEquals("{\"a\": \"INTEGER\", \"b\": {\"c\": \"VARCHAR\"}}",
                SparkDialectFormats.sparkSchemaToDuckJson("STRUCT<a INT, b STRUCT<c STRING>>"));
        assertEquals("{\"a\": [\"INTEGER\"]}",
                SparkDialectFormats.sparkSchemaToDuckJson("a ARRAY<INT>"));
        assertEquals("{\"a\": [{\"x\": \"INTEGER\"}]}",
                SparkDialectFormats.sparkSchemaToDuckJson("STRUCT<a ARRAY<STRUCT<x INT>>>"));
        assertEquals("{\"e\": \"DECIMAL(10,2)\"}",
                SparkDialectFormats.sparkSchemaToDuckJson("e DECIMAL(10,2)"));
        assertEquals("{\"a\": \"VARCHAR\", \"b\": \"INTEGER\"}",
                SparkDialectFormats.sparkSchemaToDuckJson("a STRING COMMENT 'x', b INT"));
        assertEquals("{\"a\": [[\"BIGINT\"]]}",
                SparkDialectFormats.sparkSchemaToDuckJson("a ARRAY<ARRAY<BIGINT>>"));
    }

    @Test
    void rejectsUnsupportedTypes() {
        assertThrows(UnsupportedOperationException.class,
                () -> SparkDialectFormats.sparkSchemaToDuckJson("a MAP<STRING, INT>"));
        assertThrows(UnsupportedOperationException.class,
                () -> SparkDialectFormats.sparkSchemaToDuckJson("a UUID"));
    }
}
