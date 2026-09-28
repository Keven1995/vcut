package com.vcut.api.auth.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "vcut.web")
public record WebProperties(@DefaultValue("http://localhost:3000") String allowedOrigin) {}
