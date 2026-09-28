package com.vcut.api.auth.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "vcut.auth")
public record AuthProperties(
    String jwtSecret,
    @DefaultValue("15m") Duration accessTokenTtl,
    @DefaultValue("30d") Duration refreshTokenTtl,
    @DefaultValue("false") boolean refreshCookieSecure) {}
