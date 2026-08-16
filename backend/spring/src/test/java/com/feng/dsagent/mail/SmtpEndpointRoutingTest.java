package com.feng.dsagent.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpEndpointRoutingTest {

    @Test
    void reroutesOnlyTheTrustedPublicHostAndPreservesTheTlsServerName() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            RecordingSslSocketFactory delegate = new RecordingSslSocketFactory();
            SmtpEndpointRouting routing = new SmtpEndpointRouting(
                "mail.structify.cn", "127.0.0.1", "smtp.structify.cn", delegate
            );

            SmtpEndpointRouting.Route trustedRoute = routing.routeFor("mail.structify.cn", 1500);
            assertThat(trustedRoute.routed()).isTrue();
            assertThat(trustedRoute.transportHost()).isEqualTo("smtp.structify.cn");

            try (Socket client = trustedRoute.socketFactory().createSocket("mail.structify.cn", server.getLocalPort());
                 Socket accepted = server.accept()) {
                assertThat(client.isConnected()).isTrue();
                assertThat(accepted.getInetAddress().getHostAddress()).isEqualTo("127.0.0.1");
            }

            trustedRoute.sslSocketFactory().createSocket(new Socket(), "mail.structify.cn", 465, true).close();
            assertThat(delegate.lastWrappedHost()).isEqualTo("smtp.structify.cn");

            SmtpEndpointRouting.Route otherRoute = routing.routeFor("smtp.example.invalid", 1500);
            assertThat(otherRoute.routed()).isFalse();
            assertThat(otherRoute.transportHost()).isEqualTo("smtp.example.invalid");
        }
    }

    @Test
    void appliesSocketFactoriesToSpringMailSendersWithoutChangingTheStoredPublicHost() {
        RecordingSslSocketFactory delegate = new RecordingSslSocketFactory();
        SmtpEndpointRouting routing = new SmtpEndpointRouting(
            "mail.structify.cn", "10.10.10.25", "mail.structify.cn", delegate
        );
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("mail.structify.cn");
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "5000");

        routing.applyTo(sender, 5000);

        assertThat(sender.getHost()).isEqualTo("mail.structify.cn");
        assertThat(sender.getJavaMailProperties().get("mail.smtp.socketFactory")).isInstanceOf(SocketFactory.class);
        assertThat(sender.getJavaMailProperties().get("mail.smtp.ssl.socketFactory")).isInstanceOf(SSLSocketFactory.class);
        assertThat(sender.getJavaMailProperties().get("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
    }

    static final class RecordingSslSocketFactory extends SSLSocketFactory {
        private String lastWrappedHost;

        String lastWrappedHost() {
            return lastWrappedHost;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return new String[0];
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return new String[0];
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) {
            lastWrappedHost = host;
            return new FakeSslSocket();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            lastWrappedHost = host;
            return new FakeSslSocket();
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws IOException {
            lastWrappedHost = host;
            return new FakeSslSocket();
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws IOException {
            lastWrappedHost = host.getHostAddress();
            return new FakeSslSocket();
        }

        @Override
        public Socket createSocket(
            java.net.InetAddress address,
            int port,
            java.net.InetAddress localAddress,
            int localPort
        ) throws IOException {
            lastWrappedHost = address.getHostAddress();
            return new FakeSslSocket();
        }
    }

    static final class FakeSslSocket extends SSLSocket {
        @Override
        public String[] getSupportedCipherSuites() {
            return new String[0];
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return new String[0];
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
        }

        @Override
        public String[] getSupportedProtocols() {
            return new String[0];
        }

        @Override
        public String[] getEnabledProtocols() {
            return new String[0];
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
        }

        @Override
        public javax.net.ssl.SSLSession getSession() {
            return null;
        }

        @Override
        public void addHandshakeCompletedListener(javax.net.ssl.HandshakeCompletedListener listener) {
        }

        @Override
        public void removeHandshakeCompletedListener(javax.net.ssl.HandshakeCompletedListener listener) {
        }

        @Override
        public void startHandshake() {
        }

        @Override
        public void setUseClientMode(boolean mode) {
        }

        @Override
        public boolean getUseClientMode() {
            return true;
        }

        @Override
        public void setNeedClientAuth(boolean need) {
        }

        @Override
        public boolean getNeedClientAuth() {
            return false;
        }

        @Override
        public void setWantClientAuth(boolean want) {
        }

        @Override
        public boolean getWantClientAuth() {
            return false;
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
        }

        @Override
        public boolean getEnableSessionCreation() {
            return true;
        }
    }
}
