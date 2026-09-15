package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1 acceptance tests (development plan section 7): strict name binding
 * through the full Thrift pipeline. Covers USE, qualified names, LIKE,
 * CTEs, subqueries, self joins, aliases, JOIN USING and output schema
 * assertions for projection, aggregation, CAST and DECIMAL.
 */
class F1AnalyzerBindingTest {

    private static int findFreePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record Env(ThriftServer server, DuckDbExecutor executor) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            server.stop();
        }
    }

    /** Registers db "sales" with tables users(id BIGINT, name STRING, age INT) and orders(user_id BIGINT, amount DECIMAL(10,2)). */
    private Env env() throws Exception {
        DuckDbExecutor executor = new DuckDbExecutor();
        io.github.joegxx.localsql.catalog.CatalogStore store =
                new io.github.joegxx.localsql.duckdb.DuckDbCatalogStore(executor);
        CatalogService catalog = new CatalogService(store);
        catalog.createDatabase("sales", null, null);
        catalog.registerTable("sales", "users", "TABLE",
                List.of(
                        new CatalogService.ColumnDef("id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("name", new io.github.joegxx.localsql.ir.type.StringType(), true, null),
                        new CatalogService.ColumnDef("age", io.github.joegxx.localsql.ir.type.IntegralType.INT, true, null)),
                null, "duckdb", null, null, null);
        catalog.registerTable("sales", "orders", "TABLE",
                List.of(
                        new CatalogService.ColumnDef("user_id", io.github.joegxx.localsql.ir.type.IntegralType.BIGINT, true, null),
                        new CatalogService.ColumnDef("amount", new io.github.joegxx.localsql.ir.type.DecimalType(10, 2), true, null)),
                null, "duckdb", null, null, null);
        executor.run("CREATE TABLE users (id BIGINT, name VARCHAR, age INTEGER)");
        executor.run("CREATE TABLE orders (user_id BIGINT, amount DECIMAL(10,2))");
        executor.run("INSERT INTO users VALUES (1,'a',30),(2,'b',40)");
        executor.run("INSERT INTO orders VALUES (1,10.50),(1,20.25),(2,5.00)");
        // physical names for the logical tables are wired by the pipeline via
        // runtime_table; emulate the default registration the tests rely on
        ThriftServer server = new ThriftServer(catalog, executor);
        server.start(findFreePort());
        return new Env(server, executor);
    }

    // ------------------------------------------------------------------
    // USE
    // ------------------------------------------------------------------

    @Test
    void useDatabaseSwitchesBareNameResolution() throws Exception {
        try (Env env = env()) {
            assertEquals(2, env.server().executeSparkSql("SELECT id FROM sales.users").rows().size());
            env.server().executeSparkSql("USE sales");
            assertEquals(2, env.server().executeSparkSql("SELECT id FROM users").rows().size());
        }
    }

    @Test
    void useUnknownDatabaseIsRejected() throws Exception {
        try (Env env = env()) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> env.server().executeSparkSql("USE no_such_db"));
            assertTrue(e.getMessage().contains("no_such_db"), () -> e.getMessage());
        }
    }

    @Test
    void bareNameOutsideCurrentDatabaseIsRejected() throws Exception {
        try (Env env = env()) {
            // current database is default; sales.users must not resolve bare
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> env.server().executeSparkSql("SELECT id FROM users"));
            assertTrue(e.getMessage().contains("users"), () -> e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Qualified names
    // ------------------------------------------------------------------

    @Test
    void wrongDatabaseQualifierIsRejected() throws Exception {
        try (Env env = env()) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> env.server().executeSparkSql("SELECT id FROM no_such_db.users"));
            assertTrue(e.getMessage().contains("no_such_db"), () -> e.getMessage());
        }
    }

    @Test
    void wrongTableQualifierIsRejected() throws Exception {
        try (Env env = env()) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> env.server().executeSparkSql("SELECT id FROM sales.no_such_table"));
            assertTrue(e.getMessage().contains("no_such_table"), () -> e.getMessage());
        }
    }

    @Test
    void qualifiedColumnOfWrongRelationIsRejected() throws Exception {
        try (Env env = env()) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> env.server().executeSparkSql("SELECT orders.id FROM sales.users"));
            assertTrue(e.getMessage().contains("orders"), () -> e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Missing and ambiguous columns
    // ------------------------------------------------------------------

    @Test
    void missingColumnIsRejected() throws Exception {
        try (Env env = env()) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> env.server().executeSparkSql("SELECT no_col FROM sales.users"));
            assertTrue(e.getMessage().contains("no_col"), () -> e.getMessage());
        }
    }

    @Test
    void ambiguousColumnIsRejected() throws Exception {
        try (Env env = env()) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> env.server().executeSparkSql(
                            "SELECT id FROM sales.users u1 JOIN sales.users u2 ON u1.id = u2.id"));
            assertTrue(e.getMessage().toLowerCase().contains("ambigu"), () -> e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // LIKE / CTE / subquery
    // ------------------------------------------------------------------

    @Test
    void createTableLikeCopiesSchema() throws Exception {
        try (Env env = env()) {
            env.server().executeSparkSql("CREATE TABLE users_copy LIKE sales.users");
            var r = env.server().executeSparkSql("DESCRIBE users_copy");
            assertEquals(3, r.rows().size());
            assertTrue(r.rows().stream().anyMatch(row -> "name".equals(row.get(0))));
        }
    }

    @Test
    void cteResolvesAndChains() throws Exception {
        try (Env env = env()) {
            var r = env.server().executeSparkSql(
                    "WITH u AS (SELECT id, name FROM sales.users WHERE age > 30) " +
                    "SELECT name FROM u WHERE id = 2");
            assertEquals(1, r.rows().size());
            assertEquals("b", r.rows().get(0).get(0));
        }
    }

    @Test
    void cteWithUnknownColumnIsRejected() throws Exception {
        try (Env env = env()) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> env.server().executeSparkSql(
                            "WITH u AS (SELECT id FROM sales.users) SELECT no_col FROM u"));
            assertTrue(e.getMessage().contains("no_col"), () -> e.getMessage());
        }
    }

    @Test
    void correlatedScalarSubqueryResolves() throws Exception {
        try (Env env = env()) {
            var r = env.server().executeSparkSql(
                    "SELECT name FROM sales.users u WHERE " +
                    "EXISTS (SELECT 1 FROM sales.orders o WHERE o.user_id = u.id AND o.amount > 20)");
            assertEquals(1, r.rows().size());
            assertEquals("a", r.rows().get(0).get(0));
        }
    }

    @Test
    void scalarSubqueryInsideSelectResolves() throws Exception {
        try (Env env = env()) {
            var r = env.server().executeSparkSql(
                    "SELECT name, (SELECT max(amount) FROM sales.orders) AS biggest FROM sales.users WHERE id = 2");
            assertEquals(1, r.rows().size());
            assertEquals("20.25", String.valueOf(r.rows().get(0).get(1)));
        }
    }

    // ------------------------------------------------------------------
    // Self join and aliases
    // ------------------------------------------------------------------

    @Test
    void selfJoinResolvesWithDistinctAliases() throws Exception {
        try (Env env = env()) {
            var r = env.server().executeSparkSql(
                    "SELECT a.id, b.name FROM sales.users a JOIN sales.users b ON a.id = b.id WHERE a.age = 40");
            assertEquals(1, r.rows().size());
            assertEquals("2", String.valueOf(r.rows().get(0).get(0)));
            assertEquals("b", r.rows().get(0).get(1));
        }
    }

    @Test
    void aliasShadowsTableName() throws Exception {
        try (Env env = env()) {
            // Spark semantics: after aliasing users AS u, the original table
            // name is no longer a visible qualifier
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> env.server().executeSparkSql("SELECT users.id FROM sales.users u"));
            assertTrue(e.getMessage().contains("users"), () -> e.getMessage());
            // and the alias itself works
            assertEquals(2, env.server().executeSparkSql("SELECT u.id FROM sales.users u").rows().size());
        }
    }

    // ------------------------------------------------------------------
    // JOIN USING
    // ------------------------------------------------------------------

    @Test
    void joinUsingResolvesCommonColumns() throws Exception {
        try (Env env = env()) {
            // LIKE copies the schema into the current database (default); a
            // bare-name join against it resolves through USING
            env.server().executeSparkSql("CREATE TABLE users_copy LIKE sales.users");
            env.executor().run("INSERT INTO users_copy VALUES (2,'b',40)");
            var r = env.server().executeSparkSql(
                    "SELECT count(*) AS c FROM sales.users u JOIN users_copy uc USING (id)");
            assertEquals(1, r.rows().size());
            assertEquals("1", String.valueOf(r.rows().get(0).get(0)));
        }
    }

    // ------------------------------------------------------------------
    // Output schema (F1 acceptance item 3)
    // ------------------------------------------------------------------

    @Test
    void projectionOutputUsesAliasOnly() throws Exception {
        try (Env env = env()) {
            var r = env.server().executeSparkSql("SELECT id AS x FROM sales.users");
            assertEquals(List.of("x"), r.columns());
        }
    }

    @Test
    void aggregateOutputSchema() throws Exception {
        try (Env env = env()) {
            var r = env.server().executeSparkSql(
                    "SELECT age, count(*) AS cnt FROM sales.users GROUP BY age");
            assertEquals(List.of("age", "cnt"), r.columns());
        }
    }

    @Test
    void castOutputSchema() throws Exception {
        try (Env env = env()) {
            var r = env.server().executeSparkSql("SELECT CAST(id AS STRING) AS s FROM sales.users WHERE id = 1");
            assertEquals(List.of("s"), r.columns());
            assertEquals("1", r.rows().get(0).get(0));
        }
    }

    @Test
    void decimalOutputSchema() throws Exception {
        try (Env env = env()) {
            var r = env.server().executeSparkSql("SELECT amount FROM sales.orders WHERE user_id = 1");
            assertEquals(List.of("amount"), r.columns());
            assertEquals("10.50", String.valueOf(r.rows().get(0).get(0)));
            assertEquals("20.25", String.valueOf(r.rows().get(1).get(0)));
        }
    }
}
