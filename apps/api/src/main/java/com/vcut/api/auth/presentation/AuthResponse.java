package com.vcut.api.auth.presentation;

public record AuthResponse(String accessToken, long expiresInSeconds) {}
