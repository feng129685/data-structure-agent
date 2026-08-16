package com.feng.dsagent.mail;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
final class JavaMailTransport implements MailTransport {

    private final SmtpEndpointRouting routing;

    JavaMailTransport(SmtpEndpointRouting routing) {
        this.routing = routing;
    }

    @Override
    public void testConnection(MailConnection connection, String password) {
        try {
            sender(connection, password).testConnection();
        } catch (Exception error) {
            throw new MailTransportException(error);
        }
    }

    @Override
    public void send(MailConnection connection, String password, String recipient, String subject, String html) {
        try {
            JavaMailSenderImpl sender = sender(connection, password);
            var message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(connection.fromEmail(), connection.fromName());
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
        } catch (Exception error) {
            throw new MailTransportException(error);
        }
    }

    private JavaMailSenderImpl sender(MailConnection connection, String password) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(connection.host());
        sender.setPort(connection.port());
        sender.setUsername(connection.username());
        sender.setPassword(password == null ? "" : password);
        Properties properties = sender.getJavaMailProperties();
        boolean authenticated = !connection.username().isBlank() && password != null && !password.isBlank();
        properties.put("mail.smtp.auth", Boolean.toString(authenticated));
        properties.put("mail.smtp.ssl.enable", Boolean.toString(connection.securityMode() == SecurityMode.SSL));
        properties.put("mail.smtp.ssl.checkserveridentity", "true");
        properties.put("mail.smtp.starttls.enable", Boolean.toString(connection.securityMode() == SecurityMode.STARTTLS));
        properties.put("mail.smtp.starttls.required", Boolean.toString(connection.securityMode() == SecurityMode.STARTTLS));
        int timeout = connection.connectionTimeoutSeconds() * 1000;
        properties.put("mail.smtp.connectiontimeout", Integer.toString(timeout));
        properties.put("mail.smtp.timeout", Integer.toString(timeout));
        properties.put("mail.smtp.writetimeout", Integer.toString(timeout));
        properties.put("mail.smtp.quitwait", "false");
        routing.applyTo(sender, timeout);
        return sender;
    }

    static final class MailTransportException extends RuntimeException {
        MailTransportException(Throwable cause) {
            super(cause);
        }
    }
}
