package io.github.joegxx.localsql.thrift;

import io.github.joegxx.localsql.catalog.Catalog;
import io.github.joegxx.localsql.catalog.CatalogService;
import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.duckdb.DuckDbSqlGenerator;
import io.github.joegxx.localsql.ir.relation.Relation;
import io.github.joegxx.localsql.parser.SparkSqlParser;
import io.github.joegxx.localsql.spark.SparkAstBuilder;
import org.apache.hive.service.rpc.thrift.*;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class LocalSqlThriftService implements TCLIService.Iface {

    private static final Logger LOG = LoggerFactory.getLogger(LocalSqlThriftService.class);

    private final CatalogService catalogService;
    private final DuckDbExecutor executor;
    private final DuckDbSqlGenerator generator = new DuckDbSqlGenerator();
    private final SparkSqlParser parser = new SparkSqlParser();
    private final SparkAstBuilder astBuilder = new SparkAstBuilder();

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String, OperationState> operations = new ConcurrentHashMap<>();

    LocalSqlThriftService(CatalogService catalogService, DuckDbExecutor executor) {
        this.catalogService = catalogService;
        this.executor = executor;
    }

    private record SessionState(TSessionHandle handle) {}
    private record OperationState(TOperationHandle handle, List<String> columns,
                                  List<List<Object>> rows, boolean fetched) {}

    @Override
    public TOpenSessionResp OpenSession(TOpenSessionReq req) {
        LOG.info("OpenSession user={}", req.getUsername());
        TOpenSessionResp resp = new TOpenSessionResp(ok(), TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V11);
        resp.setSessionHandle(newSessionHandle());
        resp.setConfiguration(new java.util.HashMap<>());
        return resp;
    }

    @Override
    public TCloseSessionResp CloseSession(TCloseSessionReq req) {
        sessions.remove(guid(req.getSessionHandle()));
        return new TCloseSessionResp(ok());
    }

    @Override
    public TGetInfoResp GetInfo(TGetInfoReq req) {
        TGetInfoValue val = new TGetInfoValue();
        switch (req.getInfoType()) {
            case CLI_DBMS_NAME -> val.setStringValue("Spark SQL");
            case CLI_DBMS_VER -> val.setStringValue("3.2.0");
            case CLI_SERVER_NAME -> val.setStringValue("LocalSQL");
            case CLI_MAX_COLUMN_NAME_LEN -> val.setSmallIntValue((short) 128);
            case CLI_MAX_SCHEMA_NAME_LEN -> val.setSmallIntValue((short) 128);
            case CLI_MAX_TABLE_NAME_LEN -> val.setSmallIntValue((short) 128);
            case CLI_IDENTIFIER_QUOTE_CHAR -> val.setStringValue("`");
            case CLI_SEARCH_PATTERN_ESCAPE -> val.setStringValue("\\");
            default -> val.setStringValue("");
        }
        return new TGetInfoResp(ok(), val);
    }

    @Override
    public TExecuteStatementResp ExecuteStatement(TExecuteStatementReq req) {
        String sql = req.getStatement();
        LOG.info("ExecuteStatement: {}", sql);
        TOperationHandle opHandle = newHandle(TOperationType.EXECUTE_STATEMENT, true);
        try {
            var ctx = parser.parseStatement(sql);
            Relation rel = astBuilder.buildStatement(sql, s -> parser.parseStatement(s));
            String duckSql = generator.generate(rel);
            LOG.info("Translated to DuckDB SQL: {}", duckSql);
            DuckDbExecutor.QueryResult result = executor.execute(duckSql);
            operations.put(guid(opHandle), new OperationState(opHandle, result.columns(), result.rows(), false));
        } catch (Exception e) {
            LOG.error("Execute failed", e);
            operations.put(guid(opHandle), new OperationState(opHandle, List.of("error"), List.of(List.of(e.getMessage())), false));
            TExecuteStatementResp resp = new TExecuteStatementResp(error(e.getMessage()));
            resp.setOperationHandle(opHandle);
            return resp;
        }
        TExecuteStatementResp resp = new TExecuteStatementResp(ok());
        resp.setOperationHandle(opHandle);
        return resp;
    }

    @Override
    public TGetTypeInfoResp GetTypeInfo(TGetTypeInfoReq req) {
        List<List<Object>> rows = new ArrayList<>();
        Object[][] types = {
                {"BOOLEAN", 0, null, null, null, null, 1, true, 3, null, 0, false},
                {"TINYINT", -6, 3, null, null, 1, 2, false, 3, null, 10, false},
                {"SMALLINT", 5, 5, null, null, 1, 2, false, 3, null, 10, false},
                {"INTEGER", 4, 10, null, null, 1, 2, false, 3, null, 10, false},
                {"BIGINT", -5, 19, null, null, 1, 2, false, 3, null, 10, false},
                {"FLOAT", 6, 7, null, null, 1, 2, false, 3, null, 2, false},
                {"DOUBLE", 8, 15, null, null, 1, 2, false, 3, null, 2, false},
                {"STRING", 12, null, null, null, null, 3, true, 3, null, 0, true},
                {"VARCHAR", 12, null, null, null, null, 3, true, 1, 65535, 0, true},
                {"DATE", 9, 10, null, null, 1, 2, true, 3, null, 0, false},
                {"TIMESTAMP", 11, 29, null, "'", 1, 2, true, 3, null, 0, false},
                {"DECIMAL", 3, 38, 38, null, 1, 2, false, 3, null, 10, true}
        };
        for (Object[] t : types) rows.add(List.of(t));
        TOperationHandle h = newHandle(TOperationType.GET_TYPE_INFO, true);
        operations.put(guid(h), new OperationState(h,
                List.of("TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX", "LITERAL_SUFFIX",
                        "CREATE_PARAMS", "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE", "UNSIGNED_ATTRIBUTE",
                        "FIXED_PREC_SCALE", "AUTO_INCREMENT"),
                rows, false));
        TGetTypeInfoResp r = new TGetTypeInfoResp(ok());
        r.setOperationHandle(h);
        return r;
    }

    @Override
    public TGetCatalogsResp GetCatalogs(TGetCatalogsReq req) {
        TOperationHandle h = newHandle(TOperationType.GET_CATALOGS, true);
        operations.put(guid(h), new OperationState(h, List.of("TABLE_CAT"),
                List.of(List.of("spark_catalog")), false));
        TGetCatalogsResp r = new TGetCatalogsResp(ok());
        r.setOperationHandle(h);
        return r;
    }

    @Override
    public TGetSchemasResp GetSchemas(TGetSchemasReq req) {
        List<List<Object>> rows = new ArrayList<>();
        for (Catalog.Database db : catalogService.catalog().listDatabases()) {
            rows.add(List.of(db.name(), "spark_catalog"));
        }
        TOperationHandle h = newHandle(TOperationType.GET_SCHEMAS, true);
        operations.put(guid(h), new OperationState(h, List.of("TABLE_SCHEM", "TABLE_CATALOG"), rows, false));
        TGetSchemasResp r = new TGetSchemasResp(ok());
        r.setOperationHandle(h);
        return r;
    }

    @Override
    public TGetTablesResp GetTables(TGetTablesReq req) {
        List<List<Object>> rows = new ArrayList<>();
        for (Catalog.Table t : catalogService.catalog().listTables(null)) {
            if (req.getTableName() != null && !req.getTableName().isEmpty()
                    && !matches(t.name().get(t.name().size() - 1), req.getTableName())) continue;
            rows.add(nullableRow("spark_catalog", t.name().get(0), t.name().get(1), "TABLE",
                    null, null, null, null, null, null));
        }
        TOperationHandle h = newHandle(TOperationType.GET_TABLES, true);
        operations.put(guid(h), new OperationState(h,
                List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
                        "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"),
                rows, false));
        TGetTablesResp r = new TGetTablesResp(ok());
        r.setOperationHandle(h);
        return r;
    }

    @Override
    public TGetTableTypesResp GetTableTypes(TGetTableTypesReq req) {
        TOperationHandle h = newHandle(TOperationType.GET_TABLE_TYPES, true);
        operations.put(guid(h), new OperationState(h, List.of("TABLE_TYPE"),
                List.of(List.of("TABLE")), false));
        TGetTableTypesResp r = new TGetTableTypesResp(ok());
        r.setOperationHandle(h);
        return r;
    }

    @Override
    public TGetColumnsResp GetColumns(TGetColumnsReq req) {
        List<List<Object>> rows = new ArrayList<>();
        int pos = 1;
        for (Catalog.Table t : catalogService.catalog().listTables(null)) {
            if (req.getTableName() != null && !req.getTableName().isEmpty()
                    && !matches(t.name().get(t.name().size() - 1), req.getTableName())) continue;
            for (Catalog.Column c : t.columns()) {
                rows.add(nullableRow("spark_catalog", t.name().get(0), t.name().get(1), c.name(),
                        toSqlType(c.type()), "STRING", Integer.MAX_VALUE, null, null, null,
                        java.sql.DatabaseMetaData.columnNullable, null, null, null, null,
                        (long) pos, "YES", null, null, null, null, 0));
                pos++;
            }
            pos = 1;
        }
        TOperationHandle h = newHandle(TOperationType.GET_COLUMNS, true);
        operations.put(guid(h), new OperationState(h,
                List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE",
                        "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX",
                        "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB",
                        "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG",
                        "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE"),
                rows, false));
        TGetColumnsResp r = new TGetColumnsResp(ok());
        r.setOperationHandle(h);
        return r;
    }

    @Override
    public TGetFunctionsResp GetFunctions(TGetFunctionsReq req) {
        TOperationHandle h = newHandle(TOperationType.GET_FUNCTIONS, true);
        operations.put(guid(h), new OperationState(h,
                List.of("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS", "FUNCTION_TYPE", "SPECIFIC_NAME"),
                List.of(), false));
        TGetFunctionsResp r = new TGetFunctionsResp(ok());
        r.setOperationHandle(h);
        return r;
    }

    @Override
    public TGetPrimaryKeysResp GetPrimaryKeys(TGetPrimaryKeysReq req) {
        return new TGetPrimaryKeysResp(error("Not supported"));
    }

    @Override
    public TGetCrossReferenceResp GetCrossReference(TGetCrossReferenceReq req) {
        return new TGetCrossReferenceResp(error("Not supported"));
    }

    @Override
    public TGetOperationStatusResp GetOperationStatus(TGetOperationStatusReq req) {
        OperationState op = operations.get(guid(req.getOperationHandle()));
        TGetOperationStatusResp resp = new TGetOperationStatusResp(ok());
        resp.setOperationState(TOperationState.FINISHED_STATE);
        return resp;
    }

    @Override
    public TCancelOperationResp CancelOperation(TCancelOperationReq req) {
        return new TCancelOperationResp(ok());
    }

    @Override
    public TCloseOperationResp CloseOperation(TCloseOperationReq req) {
        operations.remove(guid(req.getOperationHandle()));
        return new TCloseOperationResp(ok());
    }

    @Override
    public TGetResultSetMetadataResp GetResultSetMetadata(TGetResultSetMetadataReq req) {
        OperationState op = operations.get(guid(req.getOperationHandle()));
        TGetResultSetMetadataResp resp = new TGetResultSetMetadataResp(ok());
        TTableSchema schema = new TTableSchema();
        if (op != null) {
            for (int i = 0; i < op.columns().size(); i++) {
                schema.addToColumns(new TColumnDesc(op.columns().get(i), stringType(), i));
            }
        }
        resp.setSchema(schema);
        return resp;
    }

    @Override
    public TFetchResultsResp FetchResults(TFetchResultsReq req) {
        OperationState op = operations.get(guid(req.getOperationHandle()));
        if (op == null) {
            return new TFetchResultsResp(error("Invalid operation handle"));
        }
        if (req.getFetchType() == 1) {
            TRowSet rs = new TRowSet(0, new ArrayList<>());
            rs.setColumns(new ArrayList<>());
            TFetchResultsResp resp = new TFetchResultsResp(ok());
            resp.setResults(rs);
            return resp;
        }
        long maxRows = req.getMaxRows() > 0 ? req.getMaxRows() : 1000;
        List<List<Object>> all = op.rows();
        List<List<Object>> batch = all.size() <= maxRows ? all : all.subList(0, (int) maxRows);
        TRowSet rowSet = toRowSet(op.columns(), batch);
        TFetchResultsResp resp = new TFetchResultsResp(ok());
        resp.setResults(rowSet);
        resp.setHasMoreRows(false);
        return resp;
    }

    @Override
    public TGetDelegationTokenResp GetDelegationToken(TGetDelegationTokenReq req) {
        return new TGetDelegationTokenResp(error("Not supported"));
    }

    @Override
    public TCancelDelegationTokenResp CancelDelegationToken(TCancelDelegationTokenReq req) {
        return new TCancelDelegationTokenResp(ok());
    }

    @Override
    public TRenewDelegationTokenResp RenewDelegationToken(TRenewDelegationTokenReq req) {
        return new TRenewDelegationTokenResp(ok());
    }

    @Override
    public TGetQueryIdResp GetQueryId(TGetQueryIdReq req) {
        return new TGetQueryIdResp(UUID.randomUUID().toString());
    }

    @Override
    public TSetClientInfoResp SetClientInfo(TSetClientInfoReq req) {
        return new TSetClientInfoResp(ok());
    }

    private TRowSet toRowSet(List<String> columns, List<List<Object>> rows) {
        TRowSet rowSet = new TRowSet(0, new ArrayList<>());
        List<TColumn> tcols = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            List<String> values = new ArrayList<>();
            byte[] nulls = new byte[(rows.size() + 7) / 8];
            for (int r = 0; r < rows.size(); r++) {
                Object v = rows.get(r).size() > c ? rows.get(r).get(c) : null;
                if (v == null) {
                    nulls[r / 8] |= (byte) (1 << (r % 8));
                    values.add("");
                } else {
                    values.add(v.toString());
                }
            }
            TStringColumn sc = new TStringColumn();
            sc.setValues(values);
            sc.setNulls(ByteBuffer.wrap(nulls));
            TColumn col = new TColumn();
            col.setStringVal(sc);
            tcols.add(col);
        }
        rowSet.setColumns(tcols);
        return rowSet;
    }

    private TTypeDesc stringType() {
        TPrimitiveTypeEntry prim = new TPrimitiveTypeEntry(TTypeId.STRING_TYPE);
        TTypeEntry entry = new TTypeEntry();
        entry.setPrimitiveEntry(prim);
        TTypeDesc td = new TTypeDesc();
        td.addToTypes(entry);
        return td;
    }

    private int toSqlType(io.github.joegxx.localsql.ir.type.DataType t) {
        return Types.VARCHAR;
    }

    private boolean matches(String value, String pattern) {
        if (pattern == null || pattern.isEmpty()) return true;
        return value.matches(pattern.replace("%", ".*").replace("_", "."));
    }

    private static List<Object> nullableRow(Object... values) {
        List<Object> list = new ArrayList<>(values.length);
        for (Object v : values) list.add(v);
        return list;
    }

    private TSessionHandle newSessionHandle() {
        THandleIdentifier id = newHandleId();
        TSessionHandle h = new TSessionHandle(id);
        sessions.put(key(id.getGuid()), new SessionState(h));
        return h;
    }

    private TOperationHandle newHandle(TOperationType type, boolean hasResultSet) {
        THandleIdentifier id = newHandleId();
        TOperationHandle h = new TOperationHandle(id, type, hasResultSet);
        return h;
    }

    private THandleIdentifier newHandleId() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer guid = ByteBuffer.wrap(toBytes(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()));
        ByteBuffer secret = ByteBuffer.wrap(toBytes(uuid.hashCode(), System.nanoTime()));
        return new THandleIdentifier(guid, secret);
    }

    private byte[] toBytes(long a, long b) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(a).putLong(b);
        return buf.array();
    }

    private String key(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }
    private String guid(TSessionHandle h) { return key(h.getSessionId().getGuid()); }
    private String guid(TOperationHandle h) { return key(h.getOperationId().getGuid()); }
    private static TStatus ok() { return new TStatus(TStatusCode.SUCCESS_STATUS); }
    private static TStatus error(String msg) {
        TStatus s = new TStatus(TStatusCode.ERROR_STATUS);
        s.setErrorMessage(msg);
        return s;
    }
}
