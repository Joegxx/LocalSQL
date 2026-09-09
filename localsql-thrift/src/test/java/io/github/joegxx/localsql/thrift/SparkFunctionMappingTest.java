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
 * through the full pipeline (parse == generate == DuckDB). List results are
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
        if (s.startsWith("{") && s.endsWith("}")) {
            String body = s.substring(1, s.length() - 1);
            return "{" + java.util.Arrays.stream(body.split(","))
                    .map(String::trim).sorted().reduce((a, b) -> a + "," + b).orElse("") + "}";
        }
        return s.replaceAll("\\s", "");
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(useHeadersInDisplayName = false, nullValues = {"~"}, delimiterString = " == ", quoteCharacter = '"',
            value = {
                    // strings
                    "upper('abc') == ABC",
                    "lower('ABC') == abc",
                    "reverse('abc') == cba",
                    "ascii('A') == 65",
                    "chr(65) == A",
                    "lpad('abc', 5, 'x') == xxabc",
                    "rpad('abc', 5, 'x') == abcxx",
                    "trim('  x  ') == x",
                    "trim('xxhixx', 'x') == hi",
                    "ltrim('  x') == x",
                    "rtrim('x  ') == x",
                    "levenshtein('kitten', 'sitting') == 3",
                    "contains('hello', 'ell') == true",
                    "startswith('hello', 'he') == true",
                    "endswith('hello', 'lo') == true",
                    "concat_ws('-', 'a', 'b') == a-b",
                    "concat_ws('-', 'a', null, 'b') == a-b",
                    "repeat('ab', 2) == abab",
                    "replace('aaa', 'a', 'b') == bbb",
                    "instr('hello', 'll') == 3",
                    "instr('hello', 'zz') == 0",
                    "locate('ll', 'hello') == 3",
                    "locate('l', 'hello', 4) == 4",
                    "regexp_replace('hello world', 'o', '0') == hell0 w0rld",
                    "regexp_extract('abc123', '[0-9]+') == 123",
                    "regexp_extract_all('a1b2', '[0-9]') == [1,2]",
                    "regexp_count('a1b2', '[0-9]') == 2",
                    "regexp_like('abc', 'b') == true",
                    "split('a,b,c', ',') == [a,b,c]",
                    "substring('hello', 2, 3) == ell",
                    "length('abc') == 3",
                    // numbers
                    "abs(-3) == 3",
                    "ceil(2.1) == 3",
                    "floor(2.9) == 2",
                    "sqrt(9.0) == 3.0",
                    "pow(2, 3) == 8.0",
                    "exp(0) == 1.0",
                    "log2(8) == 3.0",
                    "log10(100) == 2.0",
                    "log(2, 8) == 3.0",
                    "sign(-5) == -1",
                    "isnan(CAST('nan' AS DOUBLE)) == true",
                    "isinf(CAST('inf' AS DOUBLE)) == true",
                    "mod(7, 3) == 1",
                    "mod(-7, 3) == -1",
                    "pmod(-7, 3) == 2",
                    "pmod(7, -3) == -2",
                    // null handling / conditional
                    "nvl(null, 'x') == x",
                    "nvl('a', 'x') == a",
                    "nvl2(null, 'y', 'n') == n",
                    "nvl2('v', 'y', 'n') == y",
                    "if(1 > 0, 'y', 'n') == y",
                    "if(false, 'y', 'n') == n",
                    "ifnull(null, 'd') == d",
                    "nullif('a', 'a') == null",
                    "equal_null(null, null) == true",
                    "equal_null(null, 1) == false",
                    "equal_null(1, 1) == true",
                    "greatest(1, 5, 3) == 5",
                    "least(4, 2, 8) == 2",
                    // dates
                    "year(date '2020-03-05') == 2020",
                    "month(date '2020-03-05') == 3",
                    "day(date '2020-03-05') == 5",
                    "dayofmonth(date '2020-03-05') == 5",
                    "quarter(date '2020-03-05') == 1",
                    "hour(timestamp '2020-01-01 13:45:10') == 13",
                    "minute(timestamp '2020-01-01 13:45:10') == 45",
                    "second(timestamp '2020-01-01 13:45:10') == 10",
                    "dayofweek(date '2020-03-05') == 5",
                    "weekday(date '2020-03-05') == 3",
                    "dayofyear(date '2020-03-05') == 65",
                    "weekofyear(date '2021-01-01') == 53",
                    "last_day(date '2020-02-10') == 2020-02-29",
                    "date_add(date '2020-01-31', 1) == 2020-02-01",
                    "date_sub(date '2020-01-31', 1) == 2020-01-30",
                    "datediff(date '2020-03-05', date '2020-01-01') == 64",
                    "add_months(date '2016-08-31', 1) == 2016-09-30",
                    "to_date('2020-01-05') == 2020-01-05",
                    "to_timestamp('2020-01-05 10:00:00') == 2020-01-05 10:00:00",
                    // from_unixtime renders in the session timezone like Spark; assert
                    // the output format is 'yyyy-MM-dd HH:mm:ss' rather than a literal
                    "regexp_like(from_unixtime(1577836800), '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$') == true",
                    "unix_timestamp('2020-01-01 00:00:00 UTC') == 1577836800",
                    // hash / encoding
                    "md5('abc') == 900150983cd24fb0d6963f7d28e17f72",
                    "sha1('abc') == a9993e364706816aba3e25717850c26c9cd0d89d",
                    "sha2('abc', 256) == ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    "sha2('abc', 0) == ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    "hex(CAST('abc' AS BLOB)) == 616263",
                    "CAST(unhex('616263') AS VARCHAR) == abc",
                    "base64(CAST('abc' AS BINARY)) == YWJj",
                    "CAST(unbase64('YWJj') AS VARCHAR) == abc",
                    // json
                    "get_json_object('{\"a\": {\"b\": 1}}', '$.a.b') == 1",
                    "json_array_length('[1,2,3]') == 3",
                    // arrays
                    "array(1, 2, 3) == [1,2,3]",
                    "array_contains(array(1, 2, 3), 2) == true",
                    "array_position(array(3, 7, 7), 7) == 2",
                    "array_distinct(array(1, 2, 2)) == [1,2]",
                    "array_union(array(1, 2), array(2, 3)) == [1,2,3]",
                    "array_intersect(array(1, 2, 3), array(2)) == [2]",
                    "array_except(array(1, 2, 3), array(2)) == [1,3]",
                    "array_join(array('a', 'b'), '-') == a-b",
                    "array_join(array('a', null, 'b'), '-') == a-b",
                    "array_join(array('a', null, 'b'), '-', 'N') == a-N-b",
                    "element_at(array(10, 20, 30), 2) == 20",
                    "flatten(array(array(1, 2), array(3))) == [1,2,3]",
                    "cardinality(array(1, 2, 3)) == 3",
                    "size(array(1, 2)) == 2",
                    "array_max(array(1, 5, 3)) == 5",
                    "array_min(array(1, 5, 3)) == 1",
                    "array_remove(array(1, 2, 3, 2), 2) == [1,3]",
                    "slice(array(1, 2, 3, 4), 2, 2) == [2,3]",
                    "slice(array(1, 2, 3, 4), -2, 2) == [3,4]",
                    // batch 2: trig / hyperbolic (DuckDB-native)
                    "acos(1.0) == 0.0",
                    "asin(0.0) == 0.0",
                    "atan(0.0) == 0.0",
                    "atan2(0, 1) == 0.0",
                    "cos(0.0) == 1.0",
                    "sin(0.0) == 0.0",
                    "tan(0.0) == 0.0",
                    "cosh(0.0) == 1.0",
                    "sinh(0.0) == 0.0",
                    "tanh(0.0) == 0.0",
                    "degrees(0.0) == 0.0",
                    "radians(0.0) == 0.0",
                    // batch 2: strings (DuckDB-native names + aliases)
                    "lcase('ABC') == abc",
                    "ucase('abc') == ABC",
                    "char_length('abc') == 3",
                    "character_length('abc') == 3",
                    "octet_length('abc') == 3",
                    "left('hello', 2) == he",
                    "right('hello', 2) == lo",
                    "translate('hello', 'el', 'ip') == hippo",
                    "round(2.567, 2) == 2.57",
                    "factorial(5) == 120",
                    "bin(13) == 1101",
                    "sha('abc') == a9993e364706816aba3e25717850c26c9cd0d89d",
                    "signum(-5) == -1",
                    // batch 2: expr helpers
                    "expm1(0.0) == 0.0",
                    "log1p(0.0) == 0.0",
                    "hypot(3.0, 4.0) == 5.0",
                    "nanvl(CAST('nan' AS DOUBLE), 5.0) == 5.0",
                    "nanvl(1.0, 5.0) == 1.0",
                    "elt(2, 'a', 'b', 'c') == b",
                    "length(space(3)) == 3",
                    "isnull(null) == true",
                    "isnotnull(1) == true",
                    "positive(5) == 5",
                    "negative(5) == -5",
                    "string(42) == 42",
                    "int('7') == 7",
                    "bigint('7') == 7",
                    "double('3.5') == 3.5",
                    "boolean('true') == true",
                    "date('2020-01-05') == 2020-01-05",
                    "decimal('3.14', 4, 2) == 3.14",
                    // batch 2: maps / json / misc
                    "map('a', 1, 'b', 2) == {a=1,b=2}",
                    "map_keys(map('a', 1, 'b', 2)) == [a,b]",
                    "map_values(map('a', 1, 'b', 2)) == [1,2]",
                    "map_from_arrays(array('a', 'b'), array(1, 2)) == {a=1,b=2}",
                    "to_json(array(1, 2)) == [1,2]",
                    "date_part('year', date '2020-03-05') == 2020",
                    "make_date(2020, 3, 5) == 2020-03-05",
                    "map_concat(map('a', 1), map('b', 2)) == {a=1,b=2}",
                    // batch 2: dates requiring restructure
                    "date_trunc('month', timestamp '2020-03-15 10:20:30') == 2020-03-01 00:00:00",
                    "date_trunc('YEAR', timestamp '2020-03-15 10:20:30') == 2020-01-01 00:00:00",
                    "months_between('2021-03-15', '2021-01-15') == 2.0",
                    "months_between('2021-03-31', '2021-01-31') == 2.0",
                    "next_day(date '2020-03-05', 'Mon') == 2020-03-09",
                    "next_day(date '2020-03-05', 'Friday') == 2020-03-06",
                    "date_from_unix_date(18262) == 2020-01-01",
                    "unix_date(date '2020-01-01') == 18262",
                    "unix_seconds(timestamp '1970-01-01 00:00:01') == 1",
                    "unix_millis(timestamp '1970-01-01 00:00:00.001') == 1",
                    "unix_micros(timestamp '1970-01-01 00:00:00.000001') == 1",
                    "to_unix_timestamp('2020-01-01 00:00:00 UTC') == 1577836800",
                    "current_catalog() == spark_catalog",
                    "find_in_set('b', 'a,b,c') == 2",
                    "find_in_set('z', 'a,b,c') == 0",
                    "substring_index('www.apache.org', '.', 2) == www.apache",
                    "substring_index('www.apache.org', '.', -1) == org",
                    "format_number(1234.567, 2) == 1,234.57",
                    // batch 3: higher-order functions (lambda)
                    "transform(array(1, 2, 3), x -> x + 1) == [2,3,4]",
                    "filter(array(1, 2, 3, 4), x -> x % 2 = 0) == [2,4]",
                    "exists(array(1, 2, 3), x -> x > 2) == true",
                    "exists(array(1, 2), x -> x > 5) == false",
                    "forall(array(2, 4), x -> x % 2 = 0) == true",
                    "forall(array(2, 3), x -> x % 2 = 0) == false",
                    "aggregate(array(1, 2, 3), 0, (acc, x) -> acc + x) == 6",
                    "aggregate(array(1, 2, 3), 1, (acc, x) -> acc * x) == 6",
                    "transform(array('a', 'b'), x -> concat(x, '!')) == [a!,b!]",
                    // batch 4: map lambdas + date_format
                    "transform_values(map('a', 1, 'b', 2), (k, v) -> v * 10) == {a=10,b=20}",
                    "transform_keys(map('a', 1, 'b', 2), (k, v) -> upper(k)) == {A=1,B=2}",
                    "map_filter(map('a', 1, 'b', 2), (k, v) -> v > 1) == {b=2}",
                    "date_format(timestamp '2020-03-05 13:45:10', 'yyyy-MM-dd') == 2020-03-05",
                    "date_format(timestamp '2020-03-05 13:45:10', 'yyyy-MM-dd HH:mm:ss') == 2020-03-05 13:45:10",
                    "date_format(timestamp '2020-03-05 13:45:10', 'yyyy/MM/dd') == 2020/03/05",
                    "date_format(timestamp '2020-03-05 13:45:10', 'yyyyMMdd') == 20200305",
                    "date_format(timestamp '2020-03-05 13:45:10', 'HH:mm:ss') == 13:45:10",
                    "date_format(date '2020-03-05', 'MMM') == Mar",
                    "date_format(date '2020-03-05', 'EEE') == Thu",
                    // batch 5: arrays / misc
                    "sort_array(array(3, 1, 2)) == [1,2,3]",
                    "sort_array(array(3, 1, 2), false) == [3,2,1]",
                    "sort_array(array(3, 1, 2), true) == [1,2,3]",
                    "sequence(1, 5) == [1,2,3,4,5]",
                    "sequence(1, 5, 2) == [1,3,5]",
                    "sequence(5, 1) == [5,4,3,2,1]",
                    "json_object_keys('{\"a\":1,\"b\":2}') == [a,b]",
                    // batch 6: structs / json / date_format millis
                    "date_format(timestamp '2020-03-05 13:45:10.123456', 'yyyy-MM-dd HH:mm:ss.SSS') == 2020-03-05 13:45:10.123",
                    "named_struct('a', 1, 'b', 'x') == {a=1,b=x}",
                    "to_json(named_struct('a', 1, 'b', 'x')) == {\"a\":1,\"b\":\"x\"}",
                    "get_field(named_struct('a', 1, 'b', 'x'), 'a') == 1",
                    "from_json('{\"a\": 1, \"b\": \"x\"}', 'STRUCT<a INT, b STRING>') == {a=1,b=x}",
                    "get_field(from_json('{\"a\": 1}', 'a INT'), 'a') == 1",
                    "get_field(from_json('{\"a\": 3.14}', 'STRUCT<a DOUBLE>'), 'a') == 3.14",
                    // batch 7: nested structs in from_json
                    "get_field(from_json('{\"a\": {\"b\": 1}}', 'STRUCT<a STRUCT<b INT>>'), 'a') == {b=1}",
                    "get_field(get_field(from_json('{\"a\": {\"b\": 1}}', 'STRUCT<a STRUCT<b INT>>'), 'a'), 'b') == 1",
                    "get_field(from_json('{\"a\": [1, 2]}', 'STRUCT<a ARRAY<INT>>'), 'a') == [1,2]",
                    "get_field(from_json('{\"e\": 3.14}', 'STRUCT<e DECIMAL(10,2)>'), 'e') == 3.14",
                    // batch 8: bround / conv / map_zip_with / from_json NULL behavior
                    "bround(2.5, 0) == 2.0",
                    "bround(3.5, 0) == 4.0",
                    "bround(-2.5, 0) == -2.0",
                    "bround(2.567, 2) == 2.57",
                    "conv(15, 10, 16) == F",
                    "conv('255', 10, 16) == FF",
                    "conv(255, 10, 2) == 11111111",
                    "conv(-255, 10, -16) == -FF",
                    "conv('a', 10, 16) == 0",
                    "initcap('hello world') == Hello World",
                    "initcap('HELLO-WORLD foo') == Hello-World Foo",
                    "initcap('a1b2 c3d4') == A1b2 C3d4",
                    "map_zip_with(map('a', 1, 'b', 2), map('b', 3, 'c', 4), (k, v1, v2) -> coalesce(v1, 0) + coalesce(v2, 0)) == {a=1,b=5,c=4}",
                    "from_json('not json', 'STRUCT<a INT>') == null",
                    "from_json(null, 'STRUCT<a INT>') == null",
                    "to_json(null) == null",
                    // batch 9: Spark 3.5 registry backfill
                    "greatest(1, 5, 3) == 5",
                    "least(1, 5, 3) == 1",
                    "nullif(4, 4) == null",
                    "nullif(4, 5) == 4",
                    "array_sort(array(3,1,2)) == [1,2,3]",
                    "array_repeat('a', 3) == [a,a,a]",
                    "arrays_overlap(array(1,2),array(2,3)) == true",
                    "arrays_overlap(array(1,2),array(9,2)) == true",
                    "arrays_overlap(array(1,2),array(8,9)) == false",
                    "bit_length('abc') == 24",
                    "bit_count(7) == 3",
                    "getbit(5, 0) == 1",
                    "getbit(5, 1) == 0",
                    "bit_get(5, 2) == 1",
                    "bit_and(6, 3) == 2",
                    "bit_or(6, 3) == 7",
                    "bit_xor(6, 3) == 5",
                    "shiftleft(1, 4) == 16",
                    "shiftright(8, 2) == 2",
                    "shiftrightunsigned(-8, 1) == 9223372036854775804",
                    "try_add(1, 2) == 3",
                    "format_string('%s=%d', 'a', 5) == a=5",
                    "rint(2.6) == 3",
                    "rint(2.4) == 2",
                    "from_utc_timestamp('2026-01-01 08:00:00', 'America/New_York') == 2026-01-01 03:00:00",
                    "to_utc_timestamp('2026-01-01 03:00:00', 'America/New_York') == 2026-01-01 08:00:00",
                    "CASE WHEN unix_timestamp(localtimestamp()) > 0 THEN 1 ELSE 0 END == 1",
                    "CASE WHEN unix_timestamp(localtimestamp()) < 4102444800 THEN 1 ELSE 0 END == 1",
                    "CAST(timestamp_seconds(1700000000) AS STRING) == 2023-11-15 06:13:20",
                    "CAST(timestamp_millis(1700000000500) AS STRING) == 2023-11-15 06:13:20.5",
                    // batch 10: json_tuple / zip_with / reflect / parse_url
                    "json_tuple('{\"a\":1,\"b\":\"x\"}', 'a', 'b') == 1",
                    "zip_with(array(1,2), array(10,20), (x, y) -> x + y) == [11,22]",
                    "zip_with(array(1,2), array(10,20,30), (x, y) -> coalesce(x, 0) + coalesce(y, 0)) == [11,22,30]",
                    "zip_with(array(1), array(10,20), (x, y) -> coalesce(x, 0) + coalesce(y, 0)) == [11,20]",
                    "reflect('java.lang.Math', 'max', 3, 7) == 7",
                    "reflect('java.lang.Math', 'abs', -5) == 5",
                    "java_method('java.lang.Math', 'min', 3, 7) == 3",
                    "parse_url('https://user:pw@example.com:8080/a/b?x=1&y=2#frag', 'HOST') == example.com",
                    "parse_url('https://example.com/a/b?x=1', 'PATH') == /a/b",
                    "parse_url('https://example.com/a/b?x=1&y=2', 'QUERY', 'y') == 2",
                    "parse_url('https://example.com/a?x=1', 'PROTOCOL') == https",
                    "parse_url('https://example.com/a?x=1#sec', 'REF') == sec"
            })
    void mapsSparkFunction(String expr, String expected) throws Exception {
        assertEquals(normalize(expected), normalize(eval(expr)), () -> expr);
    }
}
