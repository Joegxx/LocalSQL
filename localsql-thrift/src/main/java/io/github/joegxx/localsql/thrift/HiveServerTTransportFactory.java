package io.github.joegxx.localsql.thrift;

import org.apache.thrift.transport.TSaslServerTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-connection transport factory accepting both SASL PLAIN clients
 * (standard Hive JDBC, e.g. DataGrip/IntelliJ) and raw NOSASL clients
 * (our smoke-test client, jdbc:hive2://...;auth=noSasl URLs).
 *
 * SASL handshakes begin with a 0x00/0x01 version byte; a raw Thrift
 * TBinaryProtocol message starts with 0x80. We peek the first byte from
 * the socket (cached in a buffering transport so later reads see it again)
 * and wrap the connection with libthrift's TSaslServerTransport (PLAIN,
 * registered through our JCA provider) when it looks like SASL.
 *
 * This mirrors Spark's PlainSaslHelper.getPlainTransportFactory, extended
 * with per-connection SASL/NOSASL detection so a single server can serve
 * both client styles.
 */
final class HiveServerTTransportFactory extends TTransportFactory {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(HiveServerTTransportFactory.class);

    private final TSaslServerTransport.Factory saslFactory;

    HiveServerTTransportFactory() {
        // PlainSaslServer registers its JCA provider in its static initializer
        saslFactory = new TSaslServerTransport.Factory();
        saslFactory.addServerDefinition("PLAIN", "NONE", null, new HashMap<String, String>(),
                PlainSaslServer.acceptAnyHandler());
    }

    // TThreadPoolServer calls getTransport once for input and once for output;
    // both must see the same buffered (and possibly SASL-wrapped) instance.
    private final Map<TSocket, TTransport> bySocket = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public TTransport getTransport(TTransport base) {
        TSocket socket = (TSocket) base;
        TTransport cached = bySocket.get(socket);
        if (cached != null) return cached;
        try {
            BufferedSocketTransport buffered = new BufferedSocketTransport(socket);
            TTransport result;
            int first = buffered.firstByte();
            if (first == 0 || first == 1) {
                LOG.info("Connection first byte={} -> SASL path", first);
                result = saslFactory.getTransport(buffered);
            } else {
                result = buffered;
            }
            bySocket.put(socket, result);
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to detect transport: " + e.getMessage(), e);
        }
    }

    /** Reads the first byte up front, then serves it first on the next read. */
    static final class BufferedSocketTransport extends TTransport {
        private final TSocket socket;
        private final InputStream in;
        private final int firstByte;
        private boolean consumed = false;

        BufferedSocketTransport(TSocket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getSocket().getInputStream();
            this.firstByte = in.read();
        }

        int firstByte() { return firstByte; }

        OutputStream outputStream() {
            try {
                return socket.getSocket().getOutputStream();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean isOpen() { return socket.isOpen(); }

        @Override
        public void open() {}

        @Override
        public void close() { socket.close(); }

        @Override
        public int read(byte[] buf, int off, int len) throws org.apache.thrift.transport.TTransportException {
            if (!consumed) {
                consumed = true;
                if (firstByte < 0) return 0;
                buf[off] = (byte) firstByte;
                return 1;
            }
            try {
                int n = in.read(buf, off, len);
                if (n < 0) throw new org.apache.thrift.transport.TTransportException(
                        org.apache.thrift.transport.TTransportException.END_OF_FILE, "EOF");
                return n;
            } catch (IOException e) {
                throw new org.apache.thrift.transport.TTransportException(e);
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) throws org.apache.thrift.transport.TTransportException {
            socket.write(buf, off, len);
        }

        @Override
        public void flush() throws org.apache.thrift.transport.TTransportException {
            socket.flush();
        }
    }
}
