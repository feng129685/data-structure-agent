package com.feng.dsagent.auth;

public interface VerificationCodeManager {
    VerificationCodeDelivery request(String email, String purpose);
    void consume(String email, String purpose, String code);
}
