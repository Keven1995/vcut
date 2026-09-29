package com.vcut.api.video.infrastructure;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vcut.upload")
public record UploadProperties(
    long maxFileSizeBytes,
    long maxDurationSeconds,
    List<String> acceptedExtensions,
    List<String> acceptedContentTypes) {}
