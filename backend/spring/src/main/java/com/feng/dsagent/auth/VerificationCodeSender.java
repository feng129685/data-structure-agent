package com.feng.dsagent.auth;

public interface VerificationCodeSender {
    void send(String email, String code, String purpose);
}
