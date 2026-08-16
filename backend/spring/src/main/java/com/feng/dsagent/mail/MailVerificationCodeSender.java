package com.feng.dsagent.mail;

import com.feng.dsagent.auth.VerificationCodeSender;
import org.springframework.stereotype.Component;

@Component
final class MailVerificationCodeSender implements VerificationCodeSender {

    private final MailConfigService mail;

    MailVerificationCodeSender(MailConfigService mail) {
        this.mail = mail;
    }

    @Override
    public void send(String email, String code, String purpose) {
        mail.sendVerification(email, code, purpose);
    }
}
