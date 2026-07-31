package com.feng.dsagent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@SpringBootTest(properties = {
    "SMTP_STARTTLS=true"
})
class MailConfigurationIntegrationTest {

    @Autowired
    private JavaMailSender mailSender;

    @Test
    void configuresBoundedSmtpOperationsAndHostnameVerification() {
        assertThat(mailSender).isInstanceOf(JavaMailSenderImpl.class);
        var properties = ((JavaMailSenderImpl) mailSender).getJavaMailProperties();

        assertThat(properties)
            .containsEntry("mail.smtp.connectiontimeout", "5000")
            .containsEntry("mail.smtp.timeout", "10000")
            .containsEntry("mail.smtp.writetimeout", "10000")
            .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
            .containsEntry("mail.smtp.starttls.enable", "true")
            .containsEntry("mail.smtp.starttls.required", "true");
    }
}
