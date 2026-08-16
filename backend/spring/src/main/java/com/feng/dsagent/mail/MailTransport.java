package com.feng.dsagent.mail;

interface MailTransport {
    void testConnection(MailConnection connection, String password);

    void send(MailConnection connection, String password, String recipient, String subject, String html);
}
