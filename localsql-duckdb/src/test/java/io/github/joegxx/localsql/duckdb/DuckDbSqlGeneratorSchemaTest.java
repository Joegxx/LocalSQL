package io.github.joegxx.localsql.duckdb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DuckDbSqlGeneratorSchemaTest {

    @Test
    void parsesNestedSchemas() {
        assertEquals("{\"a\": \"INTEGER\", \"b\": {\"c\": \"VARCHAR\"}}",
                DuckDbSqlGenerator.sparkSchemaToDuckJson("STRUCT<a INT, b STRUCT<c STRING>>"));
        assertEquals("{\"a\": [\"INTEGER\"]}",
                DuckDbSqlGenerator.sparkSchemaToDuckJson("a ARRAY<INT>"));
        assertEquals("{\"a\": [{\"x\": \"INTEGER\"}]}",
                DuckDbSqlGenerator.sparkSchemaToDuckJson("STRUCT<a ARRAY<STRUCT<x INT>>>"));
        assertEquals("{\"e\": \"DECIMAL(10,2)\"}",
                DuckDbSqlGenerator.sparkSchemaToDuckJson("e DECIMAL(10,2)"));
        assertEquals("{\"a\": \"VARCHAR\", \"b\": \"INTEGER\"}",
                DuckDbSqlGenerator.sparkSchemaToDuckJson("a STRING COMMENT 'x', b INT"));
        assertEquals("{\"a\": [[\"BIGINT\"]]}",
                DuckDbSqlGenerator.sparkSchemaToDuckJson("a ARRAY<ARRAY<BIGINT>>"));
    }

    @Test
    void rejectsUnsupportedTypes() {
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> DuckDbSqlGenerator.sparkSchemaToDuckJson("a MAP<STRING, INT>"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> DuckDbSqlGenerator.sparkSchemaToDuckJson("a UUID"));
    }
}
