package io.github.joegxx.localsql.duckdb;

import io.github.joegxx.localsql.ir.expression.AttributeReference;
import io.github.joegxx.localsql.ir.expression.Literal;
import io.github.joegxx.localsql.ir.relation.Project;
import io.github.joegxx.localsql.ir.relation.TableScan;
import io.github.joegxx.localsql.ir.relation.Values;
import io.github.joegxx.localsql.ir.statement.CreateTable;
import io.github.joegxx.localsql.ir.statement.CreateView;
import io.github.joegxx.localsql.ir.statement.Insert;
import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.type.StringType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DuckDbSqlGeneratorDdlTest {

    private final DuckDbSqlGenerator g = new DuckDbSqlGenerator();

    private static TableScan scan(String table) {
        return new TableScan(List.of("default", table), List.of());
    }

    @Test
    void insertIntoSelect() {
        Project p = new Project(scan("orders"),
                List.of(new AttributeReference("id", List.of("orders"))), false);
        Insert i = new Insert(List.of("default", "orders"), false, null, p);
        assertEquals("INSERT INTO orders SELECT orders.id FROM orders", g.generateInsert(i));
    }

    @Test
    void insertIntoWithColumnList() {
        Values v = new Values(List.of(
                List.of(new Literal(1L, Literal.Kind.INTEGER), new Literal("x", Literal.Kind.STRING))));
        Insert i = new Insert(List.of("t"), false, List.of("id", "name"), v);
        assertEquals("INSERT INTO t (id, name) VALUES (1, 'x')", g.generateInsert(i));
    }

    @Test
    void insertFromBareTableScan() {
        Insert i = new Insert(List.of("t"), false, null, scan("src"));
        assertEquals("INSERT INTO t src", g.generateInsert(i));
    }

    @Test
    void createTableWithTypesAndNotNull() {
        var cols = List.of(
                new CreateTable.ColumnDef("id", IntegralType.BIGINT, false, null),
                new CreateTable.ColumnDef("name", new StringType(), true, "who"));
        CreateTable ct = new CreateTable(List.of("default", "users"), false, cols, null, null);
        assertEquals("CREATE TABLE users (id BIGINT NOT NULL, name VARCHAR)",
                g.generateCreateTable(ct));
    }

    @Test
    void createTableReservedWordNameIsQuoted() {
        var cols = List.of(new CreateTable.ColumnDef("v", new StringType(), true, null));
        CreateTable ct = new CreateTable(List.of("default", "order"), false, cols, null, null);
        assertEquals("CREATE TABLE \"order\" (v VARCHAR)", g.generateCreateTable(ct));
    }

    @Test
    void createTableAsSelect() {
        Project p = new Project(scan("t1"), List.of(new AttributeReference("id", List.of("t1"))), false);
        CreateTable ct = new CreateTable(List.of("t2"), false, List.of(), p, null);
        assertEquals("CREATE TABLE t2 AS SELECT t1.id FROM t1", g.generateCreateTableAs(ct));
    }

    @Test
    void createView() {
        Project p = new Project(scan("t"), List.of(new AttributeReference("v", List.of("t"))), false);
        CreateView cv = new CreateView(List.of("v"), false, false, p);
        assertEquals("CREATE VIEW v AS SELECT t.v FROM t", g.generateCreateView(cv));
    }

    @Test
    void createOrReplaceView() {
        CreateView cv = new CreateView(List.of("v"), true, false, scan("t"));
        assertEquals("CREATE OR REPLACE VIEW v AS t", g.generateCreateView(cv));
    }
}
