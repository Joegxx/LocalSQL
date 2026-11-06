package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.Catalog;
import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.catalog.CatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbCatalogStore;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import org.apache.hive.service.rpc.thrift.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseAlterPipelineTest {

    private ThriftServer server;
    private final int port = 10097;
    private DuckDbExecutor executor;
    private CatalogService catalog;

    @BeforeEach
    void setUp() throws Exception {
        executor = new DuckDbExecutor();
        CatalogStore store = new DuckDbCatalogStore(executor);
        catalog = new CatalogService(store);
        server = new ThriftServer(catalog, executor);
        server.start(port);
        Thread.sleep(800);
    }

    @AfterEach
    void tearDown() {
        server.stop();
        try { executor.close(); } catch (Exception ignored) {}
    }

    private DuckDbExecutor.QueryResult exec(String sql) throws Exception {
        return server.executeSparkSql(sql);
    }

    @Test
    void createAndDropDatabase() throws Exception {
        exec("CREATE DATABASE db1");
        assertTrue(exec("SHOW DATABASES").rows().stream().anyMatch(r -> r.get(0).equals("db1")));
        exec("CREATE DATABASE IF NOT EXISTS db1");
        assertThrows(Exception.class, () -> exec("CREATE DATABASE db1"));
        exec("CREATE TABLE db1.t (id INT)");
        assertThrows(Exception.class, () -> exec("DROP DATABASE db1"));
        assertThrows(Exception.class, () -> exec("DROP DATABASE db1 RESTRICT"));
        exec("DROP DATABASE db1 CASCADE");
        assertFalse(exec("SHOW DATABASES").rows().stream().anyMatch(r -> r.get(0).equals("db1")));
        exec("DROP DATABASE IF EXISTS db1");
        assertThrows(Exception.class, () -> exec("DROP DATABASE db1"));
        assertThrows(Exception.class, () -> exec("DROP DATABASE default"));
    }

    @Test
    void alterDatabase() throws Exception {
        exec("CREATE DATABASE db1 COMMENT 'test' LOCATION '/tmp/db1'");
        exec("ALTER DATABASE db1 SET DBPROPERTIES ('k'='v')");
        exec("ALTER DATABASE db1 SET LOCATION '/tmp/db2'");
        exec("CREATE TABLE db1.t (id INT)");
        exec("DROP DATABASE db1 CASCADE");
    }

    @Test
    void renameTableAndView() throws Exception {
        exec("CREATE TABLE t (id INT, name STRING)");
        exec("INSERT INTO t VALUES (1, 'a')");
        exec("ALTER TABLE t RENAME TO t2");
        assertThrows(Exception.class, () -> exec("SELECT * FROM t"));
        assertEquals(1, exec("SELECT * FROM t2").rows().size());
        // runtime store: physical definition re-read from DuckDB metadata under the new name
        var t2 = catalog.getTable("default", "t2").orElseThrow();
        var tInfo = catalog.store().loadRuntimeInfo(t2.tableId()).orElseThrow();
        assertEquals("t2", tInfo.duckTableName());
        assertTrue(tInfo.createSql() != null && tInfo.createSql().contains("t2"),
                "replay DDL should reference the new name: " + tInfo.createSql());
        exec("CREATE VIEW v AS SELECT * FROM t2");
        exec("ALTER VIEW v RENAME TO v2");
        assertEquals(1, exec("SELECT * FROM v2").rows().size());
        var v2 = catalog.getTable("default", "v2").orElseThrow();
        var vInfo = catalog.store().loadRuntimeInfo(v2.tableId()).orElseThrow();
        assertEquals("v2", vInfo.duckTableName());
        assertTrue(vInfo.createSql() != null && vInfo.createSql().contains("v2"),
                "replay DDL should reference the new name: " + vInfo.createSql());
    }

    @Test
    void failedMultiColumnAddLeavesNoPartialChange() throws Exception {
        exec("CREATE TABLE t (id INT)");
        exec("INSERT INTO t VALUES (1)");
        // second column conflicts with the first: the physical ALTER fails
        // mid-transaction, so neither column may land anywhere
        assertThrows(Exception.class,
                () -> exec("ALTER TABLE t ADD COLUMNS (name STRING, name STRING)"));
        // memory: only the original column
        assertEquals(1, catalog.getTable("default", "t").orElseThrow().columns().size());
        // physical: only the original column, data intact
        assertEquals(1, executor.execute("DESCRIBE t").rows().size());
        assertEquals(1, exec("SELECT * FROM t").rows().size());
        // a later valid add still works
        exec("ALTER TABLE t ADD COLUMNS (name STRING)");
        assertEquals(2, catalog.getTable("default", "t").orElseThrow().columns().size());
    }

    @Test
    void addDropColumns() throws Exception {
        exec("CREATE TABLE t (id INT)");
        exec("ALTER TABLE t ADD COLUMNS (name STRING COMMENT 'n', age INT)");
        exec("INSERT INTO t VALUES (1, 'a', 30)");
        assertEquals(3, exec("DESCRIBE t").rows().size());
        exec("ALTER TABLE t DROP COLUMNS (age)");
        assertEquals(2, exec("DESCRIBE t").rows().size());
        exec("ALTER TABLE t ADD COLUMN extra DOUBLE");
        assertEquals(3, exec("DESCRIBE t").rows().size());
        exec("ALTER TABLE t DROP COLUMN extra");
        assertEquals(2, exec("DESCRIBE t").rows().size());
    }

    @Test
    void renameColumn() throws Exception {
        exec("CREATE TABLE t (id INT, name STRING)");
        exec("ALTER TABLE t RENAME COLUMN name TO full_name");
        assertTrue(exec("DESCRIBE t").rows().stream().anyMatch(r -> r.get(0).equals("full_name")));
        assertFalse(exec("DESCRIBE t").rows().stream().anyMatch(r -> r.get(0).equals("name")));
        exec("INSERT INTO t VALUES (1, 'a')");
        assertEquals("a", exec("SELECT full_name FROM t").rows().get(0).get(0));
    }

    @Test
    void alterColumnTypeAndNotNull() throws Exception {
        exec("CREATE TABLE t (id INT, name STRING)");
        exec("ALTER TABLE t ALTER COLUMN name TYPE VARCHAR");
        exec("INSERT INTO t VALUES (1, 'a')");
        exec("ALTER TABLE t ALTER COLUMN name SET NOT NULL");
        exec("ALTER TABLE t ALTER COLUMN name DROP NOT NULL");
        exec("ALTER TABLE t ALTER COLUMN id TYPE BIGINT");
        assertEquals("bigint", exec("DESCRIBE t").rows().stream().filter(r -> r.get(0).equals("id")).findFirst().get().get(1).toString().toLowerCase());
    }

    @Test
    void hiveChangeColumn() throws Exception {
        exec("CREATE TABLE t (id INT, name STRING)");
        exec("ALTER TABLE t CHANGE COLUMN name new_name STRING");
        assertTrue(exec("DESCRIBE t").rows().stream().anyMatch(r -> r.get(0).equals("new_name")));
        exec("ALTER TABLE t CHANGE COLUMN id id BIGINT");
        assertEquals("bigint", exec("DESCRIBE t").rows().stream().filter(r -> r.get(0).equals("id")).findFirst().get().get(1).toString().toLowerCase());
    }

    @Test
    void replaceColumns() throws Exception {
        exec("CREATE TABLE t (id INT, name STRING, age INT)");
        exec("INSERT INTO t VALUES (1, 'a', 30)");
        exec("ALTER TABLE t REPLACE COLUMNS (id BIGINT, full_name STRING)");
        var desc = exec("DESCRIBE t");
        assertEquals(2, desc.rows().size());
        assertTrue(desc.rows().stream().anyMatch(r -> r.get(0).equals("full_name")));
    }

    @Test
    void setUnsetTblProperties() throws Exception {
        exec("CREATE TABLE t (id INT)");
        exec("ALTER TABLE t SET TBLPROPERTIES ('k'='v')");
        exec("ALTER TABLE t UNSET TBLPROPERTIES ('k')");
        exec("ALTER TABLE t UNSET TBLPROPERTIES IF EXISTS ('missing')");
        assertThrows(Exception.class, () -> exec("ALTER TABLE t UNSET TBLPROPERTIES ('missing')"));
    }

    @Test
    void decimalAndIntegralMetadataFidelity() throws Exception {
        exec("CREATE TABLE d (id INT, amt DECIMAL(10,2), tiny TINYINT, small SMALLINT)");
        var amt = col(catalog, "d", "amt");
        assertEquals(new io.github.joegxx.localsql.ir.type.DecimalType(10, 2), amt.type());
        assertEquals(io.github.joegxx.localsql.ir.type.IntegralType.TINYINT, col(catalog, "d", "tiny").type());
        assertEquals(io.github.joegxx.localsql.ir.type.IntegralType.SMALLINT, col(catalog, "d", "small").type());
        // Thrift-visible DESCRIBE keeps precision
        assertTrue(exec("DESCRIBE d").rows().stream()
                .anyMatch(r -> r.get(0).equals("amt") && r.get(1).equals("decimal(10,2)")));
        // CREATE TABLE LIKE copies the physical type, not a degraded one
        exec("CREATE TABLE d2 LIKE d");
        assertTrue(executor.execute("DESCRIBE d2").rows().stream()
                .anyMatch(r -> r.get(0).equals("amt") && r.get(1).toString().equalsIgnoreCase("DECIMAL(10,2)")));
        assertEquals(new io.github.joegxx.localsql.ir.type.DecimalType(10, 2), col(catalog, "d2", "amt").type());
        // restart simulation: reload catalog from the same store
        CatalogService reloaded = new CatalogService(new DuckDbCatalogStore(executor));
        assertEquals(new io.github.joegxx.localsql.ir.type.DecimalType(10, 2), col(reloaded, "d", "amt").type());
        assertEquals(io.github.joegxx.localsql.ir.type.IntegralType.TINYINT, col(reloaded, "d", "tiny").type());
        assertEquals(io.github.joegxx.localsql.ir.type.IntegralType.SMALLINT, col(reloaded, "d", "small").type());
    }

    @Test
    void replaceColumnsPrecisionChangeApplied() throws Exception {
        exec("CREATE TABLE r (id INT, amt DECIMAL(10,2))");
        exec("INSERT INTO r VALUES (1, 1.5)");
        exec("ALTER TABLE r REPLACE COLUMNS (id INT, amt DECIMAL(12,3))");
        assertTrue(executor.execute("DESCRIBE r").rows().stream()
                .anyMatch(r -> r.get(0).equals("amt") && r.get(1).toString().equalsIgnoreCase("DECIMAL(12,3)")));
        assertEquals(new io.github.joegxx.localsql.ir.type.DecimalType(12, 3), col(catalog, "r", "amt").type());
    }

    @Test
    void replaceColumnsNullabilityAppliedPhysically() throws Exception {
        exec("CREATE TABLE r (id INT, amt DECIMAL(10,2))");
        exec("INSERT INTO r VALUES (1, 1.5)");
        exec("ALTER TABLE r REPLACE COLUMNS (id INT NOT NULL, amt DECIMAL(10,2))");
        var idRow = executor.execute("DESCRIBE r").rows().stream()
                .filter(r -> r.get(0).equals("id")).findFirst().orElseThrow();
        assertEquals("NO", idRow.get(2));
    }

    @Test
    void columnCommentsSurviveAllPaths() throws Exception {
        // CREATE TABLE with COMMENT
        exec("CREATE TABLE cc (id INT COMMENT 'pk', amt DECIMAL(10,2) COMMENT 'money')");
        assertEquals("pk", col(catalog, "cc", "id").comment());
        assertEquals("money", col(catalog, "cc", "amt").comment());
        // restart round-trip keeps comments
        CatalogService reloaded = new CatalogService(new DuckDbCatalogStore(executor));
        assertEquals("pk", col(reloaded, "cc", "id").comment());
        assertEquals("money", col(reloaded, "cc", "amt").comment());
        // CREATE TABLE LIKE copies source comments
        exec("CREATE TABLE cc2 LIKE cc");
        assertEquals("pk", col(catalog, "cc2", "id").comment());
        // ADD COLUMNS ... COMMENT lands in the catalog
        exec("ALTER TABLE cc ADD COLUMNS (name STRING COMMENT 'the-name', age INT)");
        assertEquals("the-name", col(catalog, "cc", "name").comment());
        assertNull(col(catalog, "cc", "age").comment());
        // Hive CHANGE COLUMN rename carries the old comment over
        exec("ALTER TABLE cc CHANGE COLUMN name full_name STRING");
        assertEquals("the-name", col(catalog, "cc", "full_name").comment());
        // CHANGE COLUMN with an explicit new comment replaces it
        exec("ALTER TABLE cc CHANGE COLUMN full_name nick STRING COMMENT 'nick'");
        assertEquals("nick", col(catalog, "cc", "nick").comment());
        // ALTER COLUMN COMMENT on a plain column
        exec("ALTER TABLE cc ALTER COLUMN age COMMENT 'years'");
        assertEquals("years", col(catalog, "cc", "age").comment());
        // REPLACE COLUMNS applies comments from the new definition
        exec("ALTER TABLE cc REPLACE COLUMNS (id INT COMMENT 'pk2', amt DECIMAL(10,2))");
        assertEquals("pk2", col(catalog, "cc", "id").comment());
        assertNull(col(catalog, "cc", "amt").comment());
    }

    @Test
    void quotedTableNameStoredUnquoted() throws Exception {
        exec("CREATE TABLE `My Table` (id INT)");
        var t = catalog.getTable("default", "My Table").orElseThrow();
        assertEquals("My Table", t.tableName());
        exec("INSERT INTO `My Table` VALUES (1)");
        assertEquals(1, exec("SELECT * FROM `My Table`").rows().size());
    }

    @Test
    void failedMultiStepAlterRollsBackAtomically() throws Exception {
        exec("CREATE TABLE t (id INT)");
        exec("INSERT INTO t VALUES (100000)");
        // 'extra' would be added first; the DECIMAL(3,0) conversion of 100000
        // then fails — without a transaction 'extra' would survive as a
        // half-applied ALTER.
        assertThrows(Exception.class, () ->
                exec("ALTER TABLE t REPLACE COLUMNS (extra INT, id DECIMAL(3,0))"));
        var phys = executor.execute("DESCRIBE t").rows();
        assertEquals(1, phys.size());
        assertEquals("id", phys.get(0).get(0));
        assertEquals(1, catalog.getTable("default", "t").orElseThrow().columns().size());
        // the connection is usable for the next statement after the rollback
        assertEquals(1L, ((Number) exec("SELECT count(*) FROM t").rows().get(0).get(0)).longValue());
    }

    @Test
    void tablePropertiesSurviveRestart() throws Exception {
        exec("CREATE TABLE t (id INT)");
        exec("ALTER TABLE t SET TBLPROPERTIES ('k1'='v1', 'k2'='a=b', 'k3'='multi line')");
        CatalogService reloaded = new CatalogService(new DuckDbCatalogStore(executor));
        var t = reloaded.getTable("default", "t").orElseThrow();
        assertEquals("v1", t.properties().get("k1"));
        assertEquals("a=b", t.properties().get("k2"));
        assertEquals("multi line", t.properties().get("k3"));
        // UNSET removes the row, not just the in-memory entry
        exec("ALTER TABLE t UNSET TBLPROPERTIES ('k2')");
        CatalogService reloaded2 = new CatalogService(new DuckDbCatalogStore(executor));
        var props = reloaded2.getTable("default", "t").orElseThrow().properties();
        assertFalse(props.containsKey("k2"));
        assertEquals("v1", props.get("k1"));
    }

    @Test
    void databasesSurviveRestart() throws Exception {
        // empty database + properties with special characters
        exec("CREATE DATABASE db_empty COMMENT 'desc with \\'quote\\''");
        exec("CREATE DATABASE db_full COMMENT 'full db' LOCATION '/tmp/full' "
                + "WITH DBPROPERTIES ('k1'='v1', 'k2'='a=b', 'k3'='multi line')");
        exec("CREATE TABLE db_full.t (id INT)");

        CatalogService reloaded = new CatalogService(new DuckDbCatalogStore(executor));
        // empty database survives with its description
        var empty = reloaded.catalog().getDatabase("db_empty").orElseThrow();
        assertTrue(empty.description().contains("quote"));
        // full database: description/location/properties all round-trip
        var full = reloaded.catalog().getDatabase("db_full").orElseThrow();
        assertEquals("/tmp/full", full.location());
        assertEquals("v1", full.properties().get("k1"));
        assertEquals("a=b", full.properties().get("k2"));
        assertEquals("multi line", full.properties().get("k3"));
        // and the table inside it too
        assertTrue(reloaded.getTable("db_full", "t").isPresent());
        // default is always present
        assertTrue(reloaded.catalog().getDatabase("default").isPresent());

        // ALTER properties persist
        exec("ALTER DATABASE db_empty SET DBPROPERTIES ('alt'='yes')");
        var reloaded2 = new CatalogService(new DuckDbCatalogStore(executor));
        assertEquals("yes", reloaded2.catalog().getDatabase("db_empty").orElseThrow().properties().get("alt"));

        // DROP persists
        exec("DROP DATABASE db_empty");
        var reloaded3 = new CatalogService(new DuckDbCatalogStore(executor));
        assertTrue(reloaded3.catalog().getDatabase("db_empty").isEmpty());
        // and db_full is untouched
        assertTrue(reloaded3.catalog().getDatabase("db_full").isPresent());

        // DROP CASCADE removes tables with the database
        exec("DROP DATABASE db_full CASCADE");
        var reloaded4 = new CatalogService(new DuckDbCatalogStore(executor));
        assertTrue(reloaded4.catalog().getDatabase("db_full").isEmpty());
        assertTrue(reloaded4.getTable("db_full", "t").isEmpty());
    }

    @Test
    void sessionDatabaseAndEscapedSchemaPattern() throws Exception {
        LocalSqlThriftService svc = new LocalSqlThriftService(catalog, new SqlPipeline(catalog, executor));
        var open = svc.OpenSession(
                new TOpenSessionReq(TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11));
        TSessionHandle session = open.getSessionHandle();

        exec(svc, session, "CREATE DATABASE ods_demo");
        exec(svc, session, "USE ods_demo");
        // unqualified CREATE after USE registers in the session database
        exec(svc, session, "CREATE TABLE cdr (id INT COMMENT 'x', amt DECIMAL(10,2))");
        assertTrue(catalog.getTable("ods_demo", "cdr").isPresent());
        assertFalse(catalog.getTable("default", "cdr").isPresent());
        // unqualified INSERT / SELECT / DESCRIBE resolve against the session db
        exec(svc, session, "INSERT INTO cdr VALUES (1, 1.5)");
        assertEquals("1", fetchRows(svc, session, "SELECT id FROM cdr").get(0).get(0));
        assertTrue(fetchRows(svc, session, "DESCRIBE cdr").stream().anyMatch(r -> r.get(0).equals("amt")));
        assertTrue(fetchRows(svc, session, "DESC cdr").stream().anyMatch(r -> r.get(0).equals("id")));
        assertTrue(fetchRows(svc, session, "DESCRIBE FORMATTED cdr").stream()
                .anyMatch(r -> r.get(0).equals("# Detailed Table Information")));
        assertTrue(fetchRows(svc, session, "DESCRIBEFORMATTED cdr").stream()
                .anyMatch(r -> r.get(0).equals("Database") && r.get(1).equals("ods_demo")));
        // qualified access keeps working
        assertEquals("1", fetchRows(svc, session, "SELECT id FROM ods_demo.cdr").get(0).get(0));

        // IDEA escapes the underscore in schema patterns (CLI_SEARCH_PATTERN_ESCAPE)
        TGetTablesReq tr = new TGetTablesReq(session);
        tr.setSchemaName("ods\\_demo");
        List<String> tables = fetchColumn(svc, svc.GetTables(tr).getOperationHandle(), 2);
        assertTrue(tables.contains("cdr"), "escaped-underscore schema pattern must list the table: " + tables);

        TGetColumnsReq cr = new TGetColumnsReq(session);
        cr.setSchemaName("ods\\_demo");
        cr.setTableName("cdr");
        List<String> columns = fetchColumn(svc, svc.GetColumns(cr).getOperationHandle(), 3);
        assertTrue(columns.contains("amt"), "GetColumns must list columns: " + columns);

        // plain-name schema filter still works
        TGetTablesReq tr2 = new TGetTablesReq(session);
        tr2.setSchemaName("ods_demo");
        assertTrue(fetchColumn(svc, svc.GetTables(tr2).getOperationHandle(), 2).contains("cdr"));
        // other databases are not matched by the escaped pattern
        TGetTablesReq tr3 = new TGetTablesReq(session);
        tr3.setSchemaName("other\\_db");
        assertFalse(fetchColumn(svc, svc.GetTables(tr3).getOperationHandle(), 2).contains("cdr"));

        // USE catalog.database form (IDEA sends this after USE spark_catalog)
        exec(svc, session, "USE spark_catalog.default");
        exec(svc, session, "CREATE TABLE in_default (id INT)");
        assertTrue(catalog.getTable("default", "in_default").isPresent());
    }

    @Test
    void sessionDefaultsToServerDatabaseAndConnectionOverride() throws Exception {
        catalog.createDatabase("ods_demo", "demo", null);
        LocalSqlThriftService svc = new LocalSqlThriftService(
                catalog, new SqlPipeline(catalog, executor), "ods_demo");

        // server-level default: session starts in ods_demo without any USE
        var open = svc.OpenSession(
                new TOpenSessionReq(TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11));
        TSessionHandle s1 = open.getSessionHandle();
        assertEquals("ods_demo", fetchRows(svc, s1, "SELECT current_database()").get(0).get(0));
        exec(svc, s1, "USE ``");
        assertEquals("ods_demo", fetchRows(svc, s1, "SELECT current_database()").get(0).get(0));
        // unqualified DDL resolves against the server-level default too
        exec(svc, s1, "CREATE TABLE demo_t (id INT)");
        assertTrue(catalog.getTable("ods_demo", "demo_t").isPresent());

        // connection-level "use:database" (JDBC URL schema) wins over the server default
        TOpenSessionReq req = new TOpenSessionReq(TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11);
        req.setConfiguration(java.util.Map.of("use:database", "default"));
        TSessionHandle s2 = svc.OpenSession(req).getSessionHandle();
        assertEquals("default", fetchRows(svc, s2, "SELECT current_database()").get(0).get(0));

        // GetTables with the server-level schema lists its tables (IDEA tree flow)
        TGetTablesReq tr = new TGetTablesReq(s1);
        tr.setSchemaName("ods_demo");
        assertTrue(fetchColumn(svc, svc.GetTables(tr).getOperationHandle(), 2).contains("demo_t"));

        // Spark 3.1.2 uses fetchType=1 while reading some metadata result sets.
        TGetSchemasReq schemas = new TGetSchemasReq(s1);
        schemas.setSchemaName("ods_demo");
        assertTrue(fetchColumn(svc, svc.GetSchemas(schemas).getOperationHandle(), 0, (short) 1)
                .contains("ods_demo"));

        exec(svc, s1, "USE `default`");
        assertEquals("default", fetchRows(svc, s1, "SELECT current_database()").get(0).get(0));
        exec(svc, s1, "USE DATABASE `ods_demo`");
        assertEquals("ods_demo", fetchRows(svc, s1, "SELECT current_database()").get(0).get(0));
    }

    private static void exec(LocalSqlThriftService svc, TSessionHandle session, String sql) {
        var resp = svc.ExecuteStatement(new TExecuteStatementReq(session, sql));
        assertEquals(TStatusCode.SUCCESS_STATUS, resp.getStatus().getStatusCode(),
                () -> sql + " failed: " + resp.getStatus().getErrorMessage());
    }

    private static List<List<String>> fetchRows(LocalSqlThriftService svc, TSessionHandle session, String sql) {
        var resp = svc.ExecuteStatement(new TExecuteStatementReq(session, sql));
        assertEquals(TStatusCode.SUCCESS_STATUS, resp.getStatus().getStatusCode(),
                () -> sql + " failed: " + resp.getStatus().getErrorMessage());
        var res = svc.FetchResults(
                new TFetchResultsReq(resp.getOperationHandle(), TFetchOrientation.FETCH_FIRST, 100_000));
        List<List<String>> rows = new ArrayList<>();
        var cols = res.getResults().getColumns();
        if (cols == null || cols.isEmpty()) return rows;
        int n = channelCount(cols.get(0));
        for (int r = 0; r < n; r++) {
            List<String> row = new ArrayList<>();
            for (var c : cols) row.add(channelValue(c, r));
            rows.add(row);
        }
        return rows;
    }

    private static int channelCount(org.apache.hive.service.rpc.thrift.TColumn c) {
        if (c.isSetStringVal()) return c.getStringVal().getValuesSize();
        if (c.isSetI64Val()) return c.getI64Val().getValuesSize();
        if (c.isSetI32Val()) return c.getI32Val().getValuesSize();
        if (c.isSetDoubleVal()) return c.getDoubleVal().getValuesSize();
        if (c.isSetBoolVal()) return c.getBoolVal().getValuesSize();
        if (c.isSetByteVal()) return c.getByteVal().getValuesSize();
        if (c.isSetI16Val()) return c.getI16Val().getValuesSize();
        return 0;
    }

    private static String channelValue(org.apache.hive.service.rpc.thrift.TColumn c, int r) {
        if (c.isSetStringVal()) return c.getStringVal().getValues().get(r);
        if (c.isSetI64Val()) return String.valueOf(c.getI64Val().getValues().get(r));
        if (c.isSetI32Val()) return String.valueOf(c.getI32Val().getValues().get(r));
        if (c.isSetDoubleVal()) return String.valueOf(c.getDoubleVal().getValues().get(r));
        if (c.isSetBoolVal()) return String.valueOf(c.getBoolVal().getValues().get(r));
        if (c.isSetByteVal()) return String.valueOf(c.getByteVal().getValues().get(r));
        if (c.isSetI16Val()) return String.valueOf(c.getI16Val().getValues().get(r));
        return null;
    }

    private static List<String> fetchColumn(LocalSqlThriftService svc, TOperationHandle op, int col) {
        return fetchColumn(svc, op, col, (short) 0);
    }

    private static List<String> fetchColumn(LocalSqlThriftService svc, TOperationHandle op,
                                             int col, short fetchType) {
        TFetchResultsReq request = new TFetchResultsReq(op, TFetchOrientation.FETCH_FIRST, 100_000);
        request.setFetchType(fetchType);
        var res = svc.FetchResults(request);
        List<String> out = new ArrayList<>();
        var cols = res.getResults().getColumns();
        if (cols == null || cols.isEmpty()) return out;
        for (String v : cols.get(col).getStringVal().getValues()) out.add(v);
        return out;
    }

    private static Catalog.Column col(CatalogService svc, String table, String column) {
        return svc.getTable("default", table).orElseThrow().columns().stream()
                .filter(c -> c.name().equalsIgnoreCase(column)).findFirst().orElseThrow();
    }
}
