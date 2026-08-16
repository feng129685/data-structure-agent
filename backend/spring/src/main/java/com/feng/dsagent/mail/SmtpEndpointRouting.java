package com.feng.dsagent.mail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.Properties;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
final class SmtpEndpointRouting {

    private final String trustedPublicHost;
    private final String connectHost;
    private final String tlsServerName;
    private final SSLSocketFactory sslSocketFactory;

    @Autowired
    SmtpEndpointRouting(
        @Value("${SMTP_HOST:}") String trustedPublicHost,
        @Value("${SMTP_CONNECT_HOST:}") String connectHost,
        @Value("${SMTP_TLS_SERVERNAME:}") String tlsServerName
    ) {
        this(trustedPublicHost, connectHost, tlsServerName, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    SmtpEndpointRouting(
        String trustedPublicHost,
        String connectHost,
        String tlsServerName,
        SSLSocketFactory sslSocketFactory
    ) {
        this.trustedPublicHost = normalizeHost(trustedPublicHost);
        this.connectHost = normalizeText(connectHost);
        this.tlsServerName = normalizeHost(tlsServerName);
        this.sslSocketFactory = sslSocketFactory;
    }

    void applyTo(JavaMailSenderImpl sender, int connectionTimeoutMillis) {
        Route route = routeFor(sender.getHost(), connectionTimeoutMillis);
        sender.setHost(route.transportHost());
        route.install(sender.getJavaMailProperties());
    }

    Route routeFor(String configuredHost, int connectionTimeoutMillis) {
        String normalizedConfiguredHost = normalizeHost(configuredHost);
        if (normalizedConfiguredHost.isBlank()
            || trustedPublicHost.isBlank()
            || connectHost.isBlank()
            || !trustedPublicHost.equals(normalizedConfiguredHost)) {
            return new Route(normalizedConfiguredHost, null, null);
        }
        String logicalTlsHost = tlsServerName.isBlank() ? normalizedConfiguredHost : tlsServerName;
        return new Route(
            logicalTlsHost,
            new RoutedSocketFactory(connectHost, connectionTimeoutMillis),
            new RoutedSslSocketFactory(connectHost, logicalTlsHost, connectionTimeoutMillis, sslSocketFactory)
        );
    }

    static int connectionTimeoutMillis(Properties properties) {
        Object value = properties.get("mail.smtp.connectiontimeout");
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(value.toString()), 0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String normalizeHost(String value) {
        return normalizeText(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.strip();
    }

    record Route(String transportHost, SocketFactory socketFactory, SSLSocketFactory sslSocketFactory) {

        boolean routed() {
            return socketFactory != null && sslSocketFactory != null;
        }

        void install(Properties properties) {
            if (!routed()) {
                return;
            }
            properties.put("mail.smtp.socketFactory", socketFactory);
            properties.put("mail.smtp.ssl.socketFactory", sslSocketFactory);
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        }
    }

    private static final class RoutedSocketFactory extends SocketFactory {
        private final String connectHost;
        private final int connectionTimeoutMillis;

        private RoutedSocketFactory(String connectHost, int connectionTimeoutMillis) {
            this.connectHost = connectHost;
            this.connectionTimeoutMillis = Math.max(connectionTimeoutMillis, 0);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return connect(null, 0, port);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return connect(localHost, localPort, port);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return connect(null, 0, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return connect(localAddress, localPort, port);
        }

        private Socket connect(InetAddress localHost, int localPort, int port) throws IOException {
            Socket socket = new Socket();
            if (localHost != null || localPort > 0) {
                socket.bind(new InetSocketAddress(localHost, localPort));
            }
            socket.connect(new InetSocketAddress(connectHost, port), connectionTimeoutMillis);
            return socket;
        }
    }

    private static final class RoutedSslSocketFactory extends SSLSocketFactory {
        private final String connectHost;
        private final String tlsServerName;
        private final int connectionTimeoutMillis;
        private final SSLSocketFactory delegate;

        private RoutedSslSocketFactory(
            String connectHost,
            String tlsServerName,
            int connectionTimeoutMillis,
            SSLSocketFactory delegate
        ) {
            this.connectHost = connectHost;
            this.tlsServerName = tlsServerName;
            this.connectionTimeoutMillis = Math.max(connectionTimeoutMillis, 0);
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return delegate.createSocket(socket, tlsServerName, port, autoClose);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return delegate.createSocket(connectPlain(port), tlsServerName, port, true);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return delegate.createSocket(connectPlain(localHost, localPort, port), tlsServerName, port, true);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return delegate.createSocket(connectPlain(port), tlsServerName, port, true);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return delegate.createSocket(connectPlain(localAddress, localPort, port), tlsServerName, port, true);
        }

        private Socket connectPlain(int port) throws IOException {
            return connectPlain(null, 0, port);
        }

        private Socket connectPlain(InetAddress localHost, int localPort, int port) throws IOException {
            Socket socket = new Socket();
            if (localHost != null || localPort > 0) {
                socket.bind(new InetSocketAddress(localHost, localPort));
            }
            socket.connect(new InetSocketAddress(connectHost, port), connectionTimeoutMillis);
            return socket;
        }
    }
}
