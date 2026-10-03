package com.vcut.api.clip.presentation;

import java.time.Instant;

public record RenderUrlResponse(String url, Instant expiresAt) {}
