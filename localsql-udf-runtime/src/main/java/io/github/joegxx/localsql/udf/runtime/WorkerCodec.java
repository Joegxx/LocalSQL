package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.ir.type.DecimalType;
import io.github.joegxx.localsql.udf.api.UdfBatch;
import io.github.joegxx.localsql.udf.api.UdtfResult;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Value/batch wire codec for the worker protocol (see {@link UdfWorkerMain}). */
final class WorkerCodec {

    private WorkerCodec() {}

    /**
     * Wire form of a UDF signature type: readable by UdfTypeParser.parse on
     * the worker side. DECIMAL needs precision/scale in the name; everything
     * else uses {@link DataType#typeName()} verbatim.
     */
    static String wireTypeName(DataType t) {
        if (t instanceof DecimalType d) {
            return "DECIMAL(" + d.precision() + "," + d.scale() + ")";
        }
        return t.typeName();
    }

    static void writeValue(DataOutputStream out, Object v) throws IOException {
        if (v == null) {
            out.writeByte(0);
        } else if (v instanceof String s) {
            out.writeByte(1);
            out.writeUTF(s);
        } else if (v instanceof Integer n) {
            out.writeByte(2);
            out.writeInt(n);
        } else if (v instanceof Long n) {
            out.writeByte(3);
            out.writeLong(n);
        } else if (v instanceof Double n) {
            out.writeByte(4);
            out.writeDouble(n);
        } else if (v instanceof Boolean b) {
            out.writeByte(5);
            out.writeBoolean(b);
        } else if (v instanceof Float n) {
            out.writeByte(6);
            out.writeFloat(n);
        } else if (v instanceof Short n) {
            out.writeByte(7);
            out.writeShort(n);
        } else if (v instanceof Byte n) {
            out.writeByte(8);
            out.writeByte(n);
        } else if (v instanceof BigDecimal n) {
            out.writeByte(9);
            out.writeUTF(n.toString());
        } else if (v instanceof byte[] b) {
            out.writeByte(10);
            out.writeInt(b.length);
            out.write(b);
        } else if (v instanceof java.sql.Date d) {
            out.writeByte(11);
            out.writeUTF(d.toString());
        } else if (v instanceof java.sql.Timestamp t) {
            out.writeByte(12);
            out.writeUTF(t.toString());
        } else if (v instanceof LocalDate d) {
            out.writeByte(13);
            out.writeUTF(d.toString());
        } else if (v instanceof LocalDateTime d) {
            out.writeByte(14);
            out.writeUTF(d.toString());
        } else if (v instanceof OffsetDateTime d) {
            out.writeByte(15);
            out.writeUTF(d.toString());
        } else {
            // lossy fallback for values the Hive gateways cannot consume anyway
            out.writeByte(127);
            out.writeUTF(String.valueOf(v));
        }
    }

    static Object readValue(DataInputStream in) throws IOException {
        int tag = in.readUnsignedByte();
        return switch (tag) {
            case 0 -> null;
            case 1 -> in.readUTF();
            case 2 -> in.readInt();
            case 3 -> in.readLong();
            case 4 -> in.readDouble();
            case 5 -> in.readBoolean();
            case 6 -> in.readFloat();
            case 7 -> in.readShort();
            case 8 -> in.readByte();
            case 9 -> new BigDecimal(in.readUTF());
            case 10 -> {
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                yield b;
            }
            case 11 -> java.sql.Date.valueOf(in.readUTF());
            case 12 -> java.sql.Timestamp.valueOf(in.readUTF());
            case 13 -> LocalDate.parse(in.readUTF());
            case 14 -> LocalDateTime.parse(in.readUTF());
            case 15 -> OffsetDateTime.parse(in.readUTF());
            default -> in.readUTF();
        };
    }

    static void writeBatch(DataOutputStream out, UdfBatch batch) throws IOException {
        out.writeInt(batch.columnNames().size());
        for (String c : batch.columnNames()) out.writeUTF(c);
        out.writeInt(batch.rows().size());
        for (List<Object> row : batch.rows()) {
            // rows are self-describing so ragged batches cannot desync the stream
            out.writeInt(row.size());
            for (Object cell : row) writeValue(out, cell);
        }
    }

    static UdfBatch readBatch(DataInputStream in) throws IOException {
        int cols = in.readInt();
        List<String> names = new ArrayList<>(cols);
        for (int i = 0; i < cols; i++) names.add(in.readUTF());
        int rowCount = in.readInt();
        List<List<Object>> rows = new ArrayList<>(rowCount);
        for (int r = 0; r < rowCount; r++) {
            int size = in.readInt();
            List<Object> row = new ArrayList<>(size);
            for (int c = 0; c < size; c++) row.add(readValue(in));
            rows.add(row);
        }
        return new UdfBatch(names, rows);
    }

    static void writeUdtfResult(DataOutputStream out, UdtfResult result) throws IOException {
        out.writeInt(result.columnNames().size());
        for (String c : result.columnNames()) out.writeUTF(c);
        for (String t : result.columnTypes()) out.writeUTF(t);
        out.writeInt(result.rows().size());
        for (List<Object> row : result.rows()) {
            for (Object cell : row) writeValue(out, cell);
        }
        for (Integer src : result.sourceRows()) out.writeInt(src);
    }

    static UdtfResult readUdtfResult(DataInputStream in) throws IOException {
        int cols = in.readInt();
        List<String> names = new ArrayList<>(cols);
        for (int i = 0; i < cols; i++) names.add(in.readUTF());
        List<String> types = new ArrayList<>(cols);
        for (int i = 0; i < cols; i++) types.add(in.readUTF());
        int rowCount = in.readInt();
        List<List<Object>> rows = new ArrayList<>(rowCount);
        for (int r = 0; r < rowCount; r++) {
            List<Object> row = new ArrayList<>(cols);
            for (int c = 0; c < cols; c++) row.add(readValue(in));
            rows.add(row);
        }
        List<Integer> sourceRows = new ArrayList<>(rowCount);
        for (int r = 0; r < rowCount; r++) sourceRows.add(in.readInt());
        return new UdtfResult(names, types, rows, sourceRows);
    }
}
