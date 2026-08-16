package com.feng.dsagent.mail;

import java.time.Duration;

public record MailPolicy(
    Duration verificationTtl,
    Duration resendInterval,
    Duration sessionTtl,
    String siteName,
    String verificationSubject,
    String verificationTemplateHtml
) {
}
