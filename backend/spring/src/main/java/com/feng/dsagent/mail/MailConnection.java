package com.feng.dsagent.mail;

record MailConnection(
    String siteName,
    String host,
    int port,
    SecurityMode securityMode,
    String username,
    String fromEmail,
    String fromName,
    int connectionTimeoutSeconds
) {
}

enum SecurityMode {
    NONE,
    STARTTLS,
    SSL
}
