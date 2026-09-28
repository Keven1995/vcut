package com.vcut.api.auth.application;

public record AuthResult(
    String accessToken, String refreshToken, long accessTokenExpiresInSeconds) {}
