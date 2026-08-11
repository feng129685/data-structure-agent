package com.feng.dsagent.auth;

interface VerificationCodeSender {
    void send(String email, String code, String purpose);
}
