package com.feng.dsagent.mail;

record MailConfigKeyBinding(long configurationId, String host, int port, String securityMode, String username) {
    String additionalAuthenticatedData() {
        return "mail-config:" + configurationId + "\n"
            + host + "\n"
            + port + "\n"
            + securityMode + "\n"
            + username;
    }
}
