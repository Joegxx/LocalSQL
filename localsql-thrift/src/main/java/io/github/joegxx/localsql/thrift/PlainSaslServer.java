package io.github.joegxx.localsql.thrift;

import java.io.IOException;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import javax.security.sasl.SaslServerFactory;

/**
 * SASL PLAIN server for HiveServer2-compatible clients (DataGrip/IntelliJ
 * Hive JDBC). Ported from Spark's hive-thriftserver PlainSaslServer: the Sun
 * JDK provides a PLAIN client but no server, and the provider must be
 * registered through the JCA Security API (ServiceLoader does not work for
 * Sasl.getServerFactories on JDK 9+).
 *
 * LocalSQL runs with no authentication: any credentials are accepted.
 */
public final class PlainSaslServer implements SaslServer {

    public static final String PLAIN_METHOD = "PLAIN";
    private String user;
    private final CallbackHandler handler;

    private PlainSaslServer(CallbackHandler handler) {
        this.handler = handler;
    }

    @Override
    public String getMechanismName() { return PLAIN_METHOD; }

    @Override
    public byte[] evaluateResponse(byte[] response) throws SaslException {
        try {
            // message = [authzid] UTF8NUL authcid UTF8NUL passwd
            Deque<String> tokenList = new ArrayDeque<>();
            StringBuilder messageToken = new StringBuilder();
            for (byte b : response) {
                if (b == 0) {
                    tokenList.addLast(messageToken.toString());
                    messageToken = new StringBuilder();
                } else {
                    messageToken.append((char) b);
                }
            }
            tokenList.addLast(messageToken.toString());

            if (tokenList.size() < 2 || tokenList.size() > 3) {
                throw new SaslException("Invalid message format");
            }
            String passwd = tokenList.removeLast();
            user = tokenList.removeLast();
            String authzId = tokenList.isEmpty() ? user : tokenList.removeLast();
            if (user == null || user.isEmpty()) {
                throw new SaslException("No user name provided");
            }

            NameCallback nameCallback = new NameCallback("User");
            nameCallback.setName(user);
            PasswordCallback pcCallback = new PasswordCallback("Password", false);
            pcCallback.setPassword(passwd.toCharArray());
            AuthorizeCallback acCallback = new AuthorizeCallback(user, authzId);
            handler.handle(new Callback[]{nameCallback, pcCallback, acCallback});
            if (!acCallback.isAuthorized()) {
                throw new SaslException("Authentication failed");
            }
        } catch (IllegalStateException eL) {
            throw new SaslException("Invalid message format", eL);
        } catch (IOException | UnsupportedCallbackException eI) {
            throw new SaslException("Error validating the login", eI);
        }
        return null;
    }

    @Override
    public boolean isComplete() { return user != null; }

    @Override
    public String getAuthorizationID() { return user; }

    @Override
    public byte[] unwrap(byte[] incoming, int offset, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] wrap(byte[] outgoing, int offset, int len) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getNegotiatedProperty(String propName) { return null; }

    @Override
    public void dispose() {}

    /** Accepts any credentials (LocalSQL does no authentication). */
    static CallbackHandler acceptAnyHandler() {
        return callbacks -> {
            for (Callback callback : callbacks) {
                if (callback instanceof AuthorizeCallback ac) {
                    ac.setAuthorized(true);
                }
            }
        };
    }

    public static class SaslPlainServerFactory implements SaslServerFactory {
        @Override
        public SaslServer createSaslServer(String mechanism, String protocol, String serverName,
                                           Map<String, ?> props, CallbackHandler cbh) {
            if (PLAIN_METHOD.equals(mechanism)) {
                return new PlainSaslServer(cbh);
            }
            return null;
        }

        @Override
        public String[] getMechanismNames(Map<String, ?> props) {
            return new String[]{PLAIN_METHOD};
        }
    }

    /** JCA provider so Sasl.createSaslServer("PLAIN", ...) finds the factory. */
    public static class SaslPlainProvider extends Provider {
        public SaslPlainProvider() {
            super("LocalSqlSaslPlain", 1.0, "LocalSQL Plain SASL provider");
            put("SaslServerFactory.PLAIN", SaslPlainServerFactory.class.getName());
        }
    }

    static {
        Security.addProvider(new SaslPlainProvider());
    }
}
