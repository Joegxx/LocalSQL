package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.ir.type.DecimalType;
import io.github.joegxx.localsql.ir.type.IntegralType;
import io.github.joegxx.localsql.ir.type.StringType;
import io.github.joegxx.localsql.udf.api.UdfBatch;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Worker protocol conformance (no process involved): drives
 * {@link UdfWorkerMain#runProtocol} over byte streams, covering the value
 * codec round-trip and the REGISTER/EVAL commands.
 */
class UdfWorkerProtocolTest {

    private static final Path TEST_CLASSES = Path.of(System.getProperty("user.dir"), "target", "test-classes");

    @Test
    void pingRegisterEvalOverTheWire() throws Exception {
        var request = new ByteArrayOutputStream();
        var w = new DataOutputStream(request);

        w.writeUTF("PING");
        w.writeUTF("REGISTER");
        w.writeUTF("t.upper");
        w.writeUTF("test_simple");
        w.writeUTF(TestSimpleUdf.class.getName());
        w.writeUTF("SCALAR");
        w.writeInt(1);
        w.writeUTF("STRING");
        w.writeUTF("STRING");
        w.writeUTF(TEST_CLASSES.toString());
        w.writeInt(0);
        w.writeUTF("EVAL");
        w.writeUTF("test_simple");
        w.writeInt(1);
        w.writeLong(60_000);
        WorkerCodec.writeBatch(w, new UdfBatch(List.of("s"),
                List.of(java.util.Arrays.asList("alice"),
                        java.util.Arrays.asList((Object) null),
                        java.util.Arrays.asList("bob"))));
        w.writeUTF("EVAL");          // unknown function -> ERR path
        w.writeUTF("nope");
        w.writeInt(0);
        w.writeLong(60_000);
        WorkerCodec.writeBatch(w, new UdfBatch(List.of(), List.of()));
        w.writeUTF("SHUTDOWN");
        w.flush();

        var workerOut = new ByteArrayOutputStream();
        UdfWorkerMain.runProtocol(
                new DataInputStream(new ByteArrayInputStream(request.toByteArray())),
                new DataOutputStream(workerOut));
        DataInputStream r = new DataInputStream(new ByteArrayInputStream(workerOut.toByteArray()));
        assertEquals("PONG", r.readUTF());
        assertEquals("OK", r.readUTF());   // REGISTER
        assertEquals("OK", r.readUTF());   // EVAL
        UdfBatch out = WorkerCodec.readBatch(r);
        assertEquals(3, out.rowCount());
        assertEquals("ALICE", out.rows().get(0).get(0));   // value codec carried the input over
        assertNull(out.rows().get(1).get(0));              // NULL propagates through the UDF
        assertEquals("BOB", out.rows().get(2).get(0));     // extra input cells ignored by the 1-arg udf
        assertEquals("ERR", r.readUTF());  // EVAL of unknown function
        assertEquals("UDF not registered: nope/0", r.readUTF());
        assertEquals("OK", r.readUTF());    // SHUTDOWN
    }

    @Test
    void valueCodecRoundTripsEveryTaggedType() throws Exception {
        List<Object> values = java.util.Arrays.asList(null, "x", 1, 2L, 3.5d, true, 4.5f, (short) 5, (byte) 6,
                new java.math.BigDecimal("7.89"), "b".getBytes(), java.sql.Date.valueOf("2024-01-01"),
                java.sql.Timestamp.valueOf("2024-01-01 10:11:12.5"), LocalDate.of(2024, 1, 1),
                java.time.LocalDateTime.of(2024, 1, 1, 1, 2),
                java.time.OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                List.of("fallback"));
        var buf = new ByteArrayOutputStream();
        var w = new DataOutputStream(buf);
        for (Object v : values) WorkerCodec.writeValue(w, v);
        w.flush();

        DataInputStream r = new DataInputStream(new ByteArrayInputStream(buf.toByteArray()));
        for (Object expected : values) {
            Object actual = readValue(r);
            if (expected instanceof byte[] e) {
                assertEquals(java.util.Arrays.toString(e), java.util.Arrays.toString((byte[]) actual));
            } else if (expected instanceof java.util.List) {
                assertEquals(String.valueOf(expected), actual);
            } else if (expected instanceof java.time.LocalDateTime) {
                assertEquals(expected.toString(), actual.toString());
            } else if (expected instanceof java.time.OffsetDateTime) {
                assertEquals(expected.toString(), actual.toString());
            } else if (expected instanceof java.util.List) {
                assertEquals(String.valueOf(expected), actual);
            } else {
                assertEquals(expected, actual);
            }
        }
    }

    private static Object readValue(DataInputStream in) throws IOException {
        return WorkerCodec.readValue(in);
    }

    @Test
    void wireTypeNamesParseBackThroughTheConfigParser() throws Exception {
        // the REGISTER wire contract: DataType.typeName() must be readable by
        // UdfTypeParser.parse on the worker side
        for (var t : List.of(new StringType(), IntegralType.BIGINT, IntegralType.INT,
                IntegralType.TINYINT)) {
            assertEquals(t, UdfTypeParser.parse(t.typeName()));
        }
        // decimal needs precision/scale in the wire form (see wireTypeName)
        assertEquals(new DecimalType(10, 2), UdfTypeParser.parse(WorkerCodec.wireTypeName(new DecimalType(10, 2))));
    }
}
