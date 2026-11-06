package io.github.joegxx.localsql.udf.runtime;

import io.github.joegxx.localsql.ir.type.DataType;
import io.github.joegxx.localsql.udf.api.UdfBatch;
import io.github.joegxx.localsql.udf.api.UdfDefinition;
import io.github.joegxx.localsql.udf.api.UdfGateway;
import io.github.joegxx.localsql.udf.api.UdtfResult;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Process-isolated UDF gateway: every batch is shipped over stdin/stdout to a
 * child JVM running {@link UdfWorkerMain}. A hung or crashing UDF kills only
 * the worker — this gateway detects the failure and restarts a fresh worker
 * for the next call. Each call is bounded by its timeout: on expiry the
 * worker is destroyed forcibly and the timeout surfaces as an exception.
 *
 * <p>Worker classpath: {@code workerClasspath} when given, else the current
 * process's {@code java.class.path} (the fat runtime.jar in production).
 * Memory: {@code maxHeap} like {@code 256m}, or the JVM default.
 */
public final class UdfWorkerGateway implements UdfGateway {

    private static final long STARTUP_TIMEOUT_MILLIS = 30_000;

    private final String workerClasspath;
    private final String maxHeap;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "udf-worker-reader");
        t.setDaemon(true);
        return t;
    });

    private Process process;
    private DataOutputStream out;
    private DataInputStream in;
    private final Set<String> registeredIds = new HashSet<>();

    public UdfWorkerGateway() { this(null, null); }

    public UdfWorkerGateway(String workerClasspath, String maxHeap) {
        this.workerClasspath = workerClasspath;
        this.maxHeap = maxHeap;
    }

    @Override
    public UdfBatch execute(UdfDefinition definition, UdfBatch input, long timeoutMillis) throws Exception {
        return call(definition, input, timeoutMillis, false).batch();
    }

    @Override
    public UdtfResult executeUdtf(UdfDefinition definition, UdfBatch input, long timeoutMillis) throws Exception {
        return call(definition, input, timeoutMillis, true).udtf();
    }

    private record Reply(UdfBatch batch, UdtfResult udtf) {}

    private Reply call(UdfDefinition definition, UdfBatch input, long timeoutMillis, boolean udtf) throws Exception {
        ensureStarted();
        ensureRegistered(definition);
        out.writeUTF(udtf ? "EVAL_UDTF" : "EVAL");
        out.writeUTF(definition.name());
        out.writeInt(definition.argumentTypes().size());
        out.writeLong(timeoutMillis);
        WorkerCodec.writeBatch(out, input);
        out.flush();
        String status = readReply("execute of " + definition.name(), timeoutMillis);
        if ("ERR".equals(status)) {
            throw new IllegalStateException("UDF worker error in " + definition.name() + ": " + in.readUTF());
        }
        return new Reply(udtf ? null : WorkerCodec.readBatch(in), udtf ? WorkerCodec.readUdtfResult(in) : null);
    }

    private void ensureRegistered(UdfDefinition definition) throws Exception {
        if (!registeredIds.add(definition.id())) {
            return;
        }
        out.writeUTF("REGISTER");
        out.writeUTF(definition.id());
        out.writeUTF(definition.name());
        out.writeUTF(definition.className());
        out.writeUTF(definition.kind().name());
        out.writeInt(definition.argumentTypes().size());
        for (DataType t : definition.argumentTypes()) {
            out.writeUTF(WorkerCodec.wireTypeName(t));
        }
        out.writeUTF(definition.returnType() == null ? "" : WorkerCodec.wireTypeName(definition.returnType()));
        out.writeUTF(definition.jarPath() == null ? "" : definition.jarPath());
        out.writeInt(definition.priority());
        out.flush();
        String status = readReply("register of " + definition.name(), STARTUP_TIMEOUT_MILLIS);
        if ("ERR".equals(status)) {
            registeredIds.remove(definition.id());
            throw new IllegalStateException("Failed to register UDF " + definition.name()
                    + " in worker: " + in.readUTF());
        }
    }

    private String readReply(String what, long timeoutMillis) throws Exception {
        Future<String> reply = reader.submit(() -> in.readUTF());
        try {
            return reply.get(Math.max(timeoutMillis, 1), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            killWorker();
            throw new IllegalStateException("UDF worker timed out after " + timeoutMillis
                    + "ms during " + what + "; worker process killed");
        } catch (java.util.concurrent.ExecutionException e) {
            killWorker();
            throw new IllegalStateException("UDF worker died during " + what
                    + (e.getCause() == null ? "" : ": " + e.getCause().getMessage()), e.getCause());
        }
    }

    private synchronized void ensureStarted() throws Exception {
        if (process != null && process.isAlive()) {
            return;
        }
        killWorker();
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ProcessHandle.current().info().command()
                .filter(java.util.Objects::nonNull)
                .orElseGet(() -> System.getProperty("java.home") + "/bin/java"));
        if (maxHeap != null && !maxHeap.isBlank()) {
            cmd.add("-Xmx" + maxHeap);
        }
        if (workerClasspath != null && !workerClasspath.isBlank()) {
            cmd.add("-cp");
            cmd.add(workerClasspath);
        } else {
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
        }
        cmd.add(UdfWorkerMain.class.getName());
        Process p;
        try {
            p = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start UDF worker process: " + e.getMessage(), e);
        }
        // stderr passes through to the parent's console for diagnosability
        process = p;
        out = new DataOutputStream(new BufferedOutputStream(p.getOutputStream()));
        in = new DataInputStream(new BufferedInputStream(p.getInputStream()));
        out.writeUTF("PING");
        out.flush();
        String status = readReply("worker startup", STARTUP_TIMEOUT_MILLIS);
        if (!"PONG".equals(status)) {
            killWorker();
            throw new IllegalStateException("UDF worker handshake failed: " + status);
        }
        registeredIds.clear();
    }

    private synchronized void killWorker() {
        registeredIds.clear();
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
        out = null;
        in = null;
    }

    @Override
    public synchronized void close() throws Exception {
        Process p = process;
        if (p != null) {
            try {
                out.writeUTF("SHUTDOWN");
                out.flush();
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (Exception ignored) {
                p.destroyForcibly();
            }
        }
        process = null;
        out = null;
        in = null;
        reader.shutdown();
    }
}
