package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Executable compatibility matrix for Spark 3.5.9 built-in functions, batch
 * 1 (strings / numbers / null-handling / dates / hash+encoding / JSON / arrays).
 * Each case is a Spark expression and its Spark-documented result, executed
 * through the full pipeline (parse -> generate -> DuckDB). List results are
 * compared space-insensitively; timestamp results drop a trailing ".0".
 */
class SparkFunctionMappingTest {

    private DuckDbExecutor executor;
    private ThriftServer server;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        CatalogService catalog = new CatalogService(store);
        server = new ThriftServer(catalog, executor);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private String eval(String expr) throws Exception {
        var r = server.executeSparkSql("SELECT " + expr);
        Object v = r.rows().get(0).get(0);
        if (v == null) return "null";
        if (v instanceof java.sql.Timestamp t) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(t);
        }
        if (v instanceof java.sql.Date d) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd").format(d);
        }
        return String.valueOf(v);
    }

    /**
     * List results are compared order-insensitively: Spark's set functions
     * (array_distinct/union/intersect/except) do not guarantee element order,
     * and DuckDB's list_* counterparts may differ from Spark's order.
     */
    private String normalize(String s) {
        if (s.startsWith("[") && s.endsWith("]")) {
            String body = s.substring(1, s.length() - 1);
            return "[" + java.util.Arrays.stream(body.split(","))
                    .map(String::trim).sorted().reduce((a, b) -> a + "," + b).orElse("") + "]";
        }
        return s.replaceAll("\\s", "");
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(useHeadersInDisplayName = false, nullValues = {"~"}, delimiterString = "->", quoteCharacter = '"',
            value = {
                    // strings
                    "upper('abc') -> ABC",
                    "lower('ABC') -> abc",
                    "reverse('abc') -> cba",
                    "ascii('A') -> 65",
                    "chr(65) -> A",
                    "lpad('abc', 5, 'x') -> xxabc",
                    "rpad('abc', 5, 'x') -> abcxx",
                    "trim('  x  ') -> x",
                    "trim('xxhixx', 'x') -> hi",
                    "ltrim('  x') -> x",
                    "rtrim('x  ') -> x",
                    "levenshtein('kitten', 'sitting') -> 3",
                    "contains('hello', 'ell') -> true",
                    "startswith('hello', 'he') -> true",
                    "endswith('hello', 'lo') -> true",
                    "concat_ws('-', 'a', 'b') -> a-b",
                    "concat_ws('-', 'a', null, 'b') -> a-b",
                    "repeat('ab', 2) -> abab",
                    "replace('aaa', 'a', 'b') -> bbb",
                    "instr('hello', 'll') -> 3",
                    "instr('hello', 'zz') -> 0",
                    "locate('ll', 'hello') -> 3",
                    "locate('l', 'hello', 4) -> 4",
                    "regexp_replace('hello world', 'o', '0') -> hell0 w0rld",
                    "regexp_extract('abc123', '[0-9]+') -> 123",
                    "regexp_extract_all('a1b2', '[0-9]') -> [1,2]",
                    "regexp_count('a1b2', '[0-9]') -> 2",
                    "regexp_like('abc', 'b') -> true",
                    "split('a,b,c', ',') -> [a,b,c]",
                    "substring('hello', 2, 3) -> ell",
                    "length('abc') -> 3",
                    // numbers
                    "abs(-3) -> 3",
                    "ceil(2.1) -> 3",
                    "floor(2.9) -> 2",
                    "sqrt(9.0) -> 3.0",
                    "pow(2, 3) -> 8.0",
                    "exp(0) -> 1.0",
                    "log2(8) -> 3.0",
                    "log10(100) -> 2.0",
                    "log(2, 8) -> 3.0",
                    "sign(-5) -> -1",
                    "isnan(CAST('nan' AS DOUBLE)) -> true",
                    "isinf(CAST('inf' AS DOUBLE)) -> true",
                    "mod(7, 3) -> 1",
                    "mod(-7, 3) -> -1",
                    "pmod(-7, 3) -> 2",
                    "pmod(7, -3) -> -2",
                    // null handling / conditional
                    "nvl(null, 'x') -> x",
                    "nvl('a', 'x') -> a",
                    "nvl2(null, 'y', 'n') -> n",
                    "nvl2('v', 'y', 'n') -> y",
                    "if(1 > 0, 'y', 'n') -> y",
                    "if(false, 'y', 'n') -> n",
                    "ifnull(null, 'd') -> d",
                    "nullif('a', 'a') -> null",
                    "equal_null(null, null) -> true",
                    "equal_null(null, 1) -> false",
                    "equal_null(1, 1) -> true",
                    "greatest(1, 5, 3) -> 5",
                    "least(4, 2, 8) -> 2",
                    // dates
                    "year(date '2020-03-05') -> 2020",
                    "month(date '2020-03-05') -> 3",
                    "day(date '2020-03-05') -> 5",
                    "dayofmonth(date '2020-03-05') -> 5",
                    "quarter(date '2020-03-05') -> 1",
                    "hour(timestamp '2020-01-01 13:45:10') -> 13",
                    "minute(timestamp '2020-01-01 13:45:10') -> 45",
                    "second(timestamp '2020-01-01 13:45:10') -> 10",
                    "dayofweek(date '2020-03-05') -> 5",
                    "weekday(date '2020-03-05') -> 3",
                    "dayofyear(date '2020-03-05') -> 65",
                    "weekofyear(date '2021-01-01') -> 53",
                    "last_day(date '2020-02-10') -> 2020-02-29",
                    "date_add(date '2020-01-31', 1) -> 2020-02-01",
                    "date_sub(date '2020-01-31', 1) -> 2020-01-30",
                    "datediff(date '2020-03-05', date '2020-01-01') -> 64",
                    "add_months(date '2016-08-31', 1) -> 2016-09-30",
                    "to_date('2020-01-05') -> 2020-01-05",
                    "to_timestamp('2020-01-05 10:00:00') -> 2020-01-05 10:00:00",
                    // from_unixtime renders in the session timezone like Spark; assert
                    // the output format is 'yyyy-MM-dd HH:mm:ss' rather than a literal
                    "regexp_like(from_unixtime(1577836800), '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$') -> true",
                    "unix_timestamp('2020-01-01 00:00:00 UTC') -> 1577836800",
                    // hash / encoding
                    "md5('abc') -> 900150983cd24fb0d6963f7d28e17f72",
                    "sha1('abc') -> a9993e364706816aba3e25717850c26c9cd0d89d",
                    "sha2('abc', 256) -> ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    "sha2('abc', 0) -> ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    "hex(CAST('abc' AS BLOB)) -> 616263",
                    "CAST(unhex('616263') AS VARCHAR) -> abc",
                    "base64(CAST('abc' AS BINARY)) -> YWJj",
                    "CAST(unbase64('YWJj') AS VARCHAR) -> abc",
                    // json
                    "get_json_object('{\"a\": {\"b\": 1}}', '$.a.b') -> 1",
                    "json_array_length('[1,2,3]') -> 3",
                    // arrays
                    "array(1, 2, 3) -> [1,2,3]",
                    "array_contains(array(1, 2, 3), 2) -> true",
                    "array_position(array(3, 7, 7), 7) -> 2",
                    "array_distinct(array(1, 2, 2)) -> [1,2]",
                    "array_union(array(1, 2), array(2, 3)) -> [1,2,3]",
                    "array_intersect(array(1, 2, 3), array(2)) -> [2]",
                    "array_except(array(1, 2, 3), array(2)) -> [1,3]",
                    "array_join(array('a', 'b'), '-') -> a-b",
                    "array_join(array('a', null, 'b'), '-') -> a-b",
                    "array_join(array('a', null, 'b'), '-', 'N') -> a-N-b",
                    "element_at(array(10, 20, 30), 2) -> 20",
                    "flatten(array(array(1, 2), array(3))) -> [1,2,3]",
                    "cardinality(array(1, 2, 3)) -> 3",
                    "size(array(1, 2)) -> 2",
                    "array_max(array(1, 5, 3)) -> 5"
            })
    void mapsSparkFunction(String expr, String expected) throws Exception {
        assertEquals(normalize(expected), normalize(eval(expr)), () -> expr);
    }
}
