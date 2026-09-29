package com.vcut.api.video.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "vcut.storage")
public record ObjectStorageProperties(
    @DefaultValue("true") boolean enabled,
    String endpoint,
    @DefaultValue("us-east-1") String region,
    String bucket,
    String accessKey,
    String secretKey,
    @DefaultValue("true") boolean pathStyleAccess,
    @DefaultValue("true") boolean autoCreateBucket,
    @DefaultValue("15m") Duration presignedUrlTtl) {}
