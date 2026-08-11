package com.feng.dsagent.auth;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
class MailVerificationCodeSender implements VerificationCodeSender {

    private final JavaMailSender mailSender;
    private final AuthProperties properties;

    MailVerificationCodeSender(JavaMailSender mailSender, AuthProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(String email, String code, String purpose) {
        if (!properties.mailEnabled()) {
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject("数据结构智能体验证码");
        message.setText("你的验证码是 " + code + "，请在 10 分钟内使用。用途：" + purpose + "。");
        mailSender.send(message);
    }
}
