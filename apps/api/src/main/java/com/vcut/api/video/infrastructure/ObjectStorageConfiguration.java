package com.vcut.api.video.infrastructure;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@ConditionalOnProperty(prefix = "vcut.storage", name = "enabled", havingValue = "true")
public class ObjectStorageConfiguration {

  @Bean
  S3Client s3Client(ObjectStorageProperties properties) {
    return S3Client.builder()
        .region(Region.of(properties.region()))
        .credentialsProvider(credentialsProvider(properties))
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyleAccess()).build())
        .endpointOverride(URI.create(properties.endpoint()))
        .build();
  }

  @Bean
  S3Presigner s3Presigner(ObjectStorageProperties properties) {
    return S3Presigner.builder()
        .region(Region.of(properties.region()))
        .credentialsProvider(credentialsProvider(properties))
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyleAccess()).build())
        .endpointOverride(URI.create(properties.endpoint()))
        .build();
  }

  private StaticCredentialsProvider credentialsProvider(ObjectStorageProperties properties) {
    if (isBlank(properties.accessKey()) || isBlank(properties.secretKey())) {
      throw new IllegalStateException(
          "OBJECT_STORAGE_ACCESS_KEY and OBJECT_STORAGE_SECRET_KEY are required when storage is enabled");
    }
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
