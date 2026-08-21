package io.github.joegxx.localsql.ir.statement;

import io.github.joegxx.localsql.ir.type.DataType;

import java.util.List;

/**
 * Covers:
 *  - ALTER TABLE tbl ALTER/CCHANGE COLUMN col TYPE dt / COMMENT / FIRST|AFTER
 *  - ALTER TABLE tbl ALTER COLUMN col SET/DROP NOT NULL
 *  - Hive CHANGE COLUMN old ColDef
 * Only one action is expected per statement in our subset.
 */
public final class AlterColumn extends Statement {
    private final List<String> tableName;
    private final String column;
    private final String newName; // for Hive CHANGE rename, null if not renaming
    private final DataType newType;
    private final String newComment;
    private final Boolean setNotNull; // null = no change, true = SET NOT NULL, false = DROP NOT NULL
    private final String position; // "FIRST" or "AFTER col" or null

    public AlterColumn(List<String> tableName, String column, DataType newType,
                       String newComment, Boolean setNotNull, String position) {
        this(tableName, column, null, newType, newComment, setNotNull, position);
    }

    public AlterColumn(List<String> tableName, String column, String newName, DataType newType,
                       String newComment, Boolean setNotNull, String position) {
        this.tableName = List.copyOf(tableName);
        this.column = column;
        this.newName = newName;
        this.newType = newType;
        this.newComment = newComment;
        this.setNotNull = setNotNull;
        this.position = position;
    }

    public List<String> tableName() { return tableName; }
    public String column() { return column; }
    public String newName() { return newName; }
    public DataType newType() { return newType; }
    public String newComment() { return newComment; }
    public Boolean setNotNull() { return setNotNull; }
    public String position() { return position; }

    @Override
    public String toString() { return "AlterColumn " + String.join(".", tableName) + "." + column; }
}
