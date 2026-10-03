package com.vcut.api.clip.presentation;

import java.time.Instant;

public record PreviewUrlResponse(String url, Instant expiresAt) {}
