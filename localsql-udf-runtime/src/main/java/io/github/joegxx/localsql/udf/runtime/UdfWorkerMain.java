package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.udf.api.UdfBatch;
import io.github.joegxx.localsql.udf.api.UdfDefinition;
import io.github.joegxx.localsql.udf.api.UdfKind;
import io.github.joegxx.localsql.udf.api.UdfRegistry;
import io.github.joegxx.localsql.udf.api.UdtfResult;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Worker entry point for process-isolated UDF execution. The parent runtime
 * ({@link UdfWorkerGateway}) speaks a synchronous binary protocol over the
 * worker's stdin/stdout; the worker hosts a plain in-process
 * {@link HiveUdfGateway}, so a crashing or hung UDF takes down only this
 * child JVM, never the SQL runtime.
 *
 * <p>Protocol (each message starts with a UTF command; bodies below):
 * <pre>
 * PING                        -> PONG
 * REGISTER id name class kind argCount [argTypeName...] returnTypeName jar priority
 *                             -> OK | ERR message
 * EVAL name timeoutMillis batch-> OK batch | ERR message
 * EVAL_UDTF name timeout batch-> OK udtfResult | ERR message
 * SHUTDOWN                     -> OK (worker exits)
 * </pre>
 *
 * Values are tagged: null, String, Integer, Long, Double, Boolean, Float,
 * Short, Byte, BigDecimal, byte[], java.sql.Date, java.sql.Timestamp,
 * LocalDate, LocalDateTime, OffsetDateTime; anything else falls back to its
 * string form (lossy, same support surface as the in-process gateway).
 */
public final class UdfWorkerMain {

    private UdfWorkerMain() {}

    public static void main(String[] args) throws Exception {
        int code = runProtocol(
                new DataInputStream(new BufferedInputStream(System.in)),
                new DataOutputStream(new BufferedOutputStream(System.out)));
        System.exit(code);
    }

    /** Runs one request loop; package-private for in-process protocol tests. */
    static int runProtocol(DataInputStream in, DataOutputStream out) throws IOException {
        UdfRegistry registry = new UdfRegistry();
        HiveUdfGateway gateway = new HiveUdfGateway();
        boolean running = true;
        while (running) {
            String command;
            try {
                command = in.readUTF();
            } catch (IOException eof) {
                break; // parent died
            }
            switch (command) {
                case "PING" -> {
                    out.writeUTF("PONG");
                    out.flush();
                }
                case "REGISTER" -> {
                    try {
                        registry.register(readDefinition(in));
                        out.writeUTF("OK");
                    } catch (Throwable e) {
                        error(out, e);
                    }
                    out.flush();
                }
                case "EVAL", "EVAL_UDTF" -> {
                    // consume the whole request first: a failed resolve must
                    // not leave batch bytes in the stream desyncing the next
                    // command
                    String name = in.readUTF();
                    int arity = in.readInt();
                    long timeout = in.readLong();
                    UdfBatch input = WorkerCodec.readBatch(in);
                    try {
                        UdfDefinition def = resolve(registry, name, arity);
                        if (command.equals("EVAL")) {
                            out.writeUTF("OK");
                            WorkerCodec.writeBatch(out, gateway.execute(def, input, timeout));
                        } else {
                            out.writeUTF("OK");
                            WorkerCodec.writeUdtfResult(out, gateway.executeUdtf(def, input, timeout));
                        }
                    } catch (Throwable e) {
                        error(out, e);
                    }
                    out.flush();
                }
                case "SHUTDOWN" -> {
                    out.writeUTF("OK");
                    out.flush();
                    running = false;
                }
                default -> {
                    error(out, new IllegalArgumentException("unknown command: " + command));
                    out.flush();
                }
            }
        }
        try {
            gateway.close();
        } catch (Exception ignored) {}
        return 0;
    }

    private static UdfDefinition resolve(UdfRegistry registry, String name, int arity) {
        return registry.resolveByName(name, arity).orElseThrow(() ->
                new IllegalArgumentException("UDF not registered: " + name + "/" + arity));
    }

    private static UdfDefinition readDefinition(DataInputStream in) throws IOException {
        String id = in.readUTF();
        String name = in.readUTF();
        String className = in.readUTF();
        UdfKind kind = UdfKind.valueOf(in.readUTF());
        int argCount = in.readInt();
        java.util.List<DataType> args = new java.util.ArrayList<>(argCount);
        for (int i = 0; i < argCount; i++) {
            args.add(UdfTypeParser.parse(in.readUTF()));
        }
        String returnTypeName = in.readUTF();
        DataType returns = returnTypeName.isEmpty() ? null : UdfTypeParser.parse(returnTypeName);
        String jarPath = in.readUTF();
        int priority = in.readInt();
        return new UdfDefinition(id, name, className, kind, args, returns, jarPath, "3.1", priority);
    }

    private static void error(DataOutputStream out, Throwable e) throws IOException {
        out.writeUTF("ERR");
        out.writeUTF(String.valueOf(e.getMessage()));
    }
}
