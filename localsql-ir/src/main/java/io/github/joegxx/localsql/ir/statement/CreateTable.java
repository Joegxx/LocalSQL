package io.github.joegxx.localsql.ir.statement;

import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.ir.type.DataType;

import java.util.List;

/**
 * CREATE TABLE. Covers three shapes:
 *  - explicit columns: CREATE TABLE t (c1 T1, ...) [AS query]
 *  - CTAS: CREATE TABLE t AS query (columns empty, query non-null)
 *  - LIKE: CREATE TABLE t LIKE src (likeSource non-null, columns empty)
 * PARTITIONED BY columns declared with a type were already appended to
 * `columns` by the AST builder (partitioning is a storage detail DuckDB does
 * not have); partition-by references to existing columns are schema-neutral.
 */
public final class CreateTable extends Statement {
    private final List<String> tableName;
    private final boolean ifNotExists;
    private final List<ColumnDef> columns;
    private final Relation query;
    private final List<String> likeSource;

    public CreateTable(List<String> tableName, boolean ifNotExists,
                       List<ColumnDef> columns, Relation query, List<String> likeSource) {
        this.tableName = List.copyOf(tableName);
        this.ifNotExists = ifNotExists;
        this.columns = List.copyOf(columns);
        this.query = query;
        this.likeSource = likeSource == null ? null : List.copyOf(likeSource);
    }

    public List<String> tableName() { return tableName; }
    public boolean ifNotExists() { return ifNotExists; }
    public List<ColumnDef> columns() { return columns; }
    public Relation query() { return query; }
    public List<String> likeSource() { return likeSource; }

    public record ColumnDef(String name, DataType type, boolean nullable, String comment) {}

    @Override
    public String toString() {
        return "CreateTable " + String.join(".", tableName)
                + (likeSource != null ? " LIKE " + String.join(".", likeSource) : "");
    }
}
