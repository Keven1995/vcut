package com.vcut.api.shared.health;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "vcut.dependencies")
public record DependencyProperties(
    @DefaultValue("localhost") String postgresHost,
    @DefaultValue("5432") int postgresPort,
    @DefaultValue("localhost") String rabbitmqHost,
    @DefaultValue("5672") int rabbitmqPort,
    @DefaultValue("http://localhost:9000/minio/health/live") String storageHealthUrl) {}
