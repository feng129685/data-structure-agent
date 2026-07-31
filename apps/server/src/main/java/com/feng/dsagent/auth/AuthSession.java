package com.feng.dsagent.auth;

public record AuthSession(String token, UserView user) {
}
