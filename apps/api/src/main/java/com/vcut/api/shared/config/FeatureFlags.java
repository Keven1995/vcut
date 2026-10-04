package com.vcut.api.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vcut.feature-flags")
public record FeatureFlags(
    boolean aiSmartCrop,
    boolean faceTracking,
    boolean multimodalAnalysis,
    boolean autoZoom,
    boolean externalVideoImport) {}
