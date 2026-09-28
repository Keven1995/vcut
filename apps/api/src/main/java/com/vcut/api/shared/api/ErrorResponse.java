package com.vcut.api.shared.api;

public record ErrorResponse(String code, String message, String traceId) {}
