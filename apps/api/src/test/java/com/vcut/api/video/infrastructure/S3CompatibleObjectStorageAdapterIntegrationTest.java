package com.vcut.api.video.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.vcut.api.video.application.ObjectStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Testcontainers
class S3CompatibleObjectStorageAdapterIntegrationTest {

  private static final String IMAGE =
      "cleanstart/minio@sha256:25a88377f4c16dad1d93fbbecc95365b72bb9c32ed59b74b4bc047234d2e4578";
  private static final String ACCESS_KEY = "test-" + UUID.randomUUID().toString().replace("-", "");
  private static final String SECRET_KEY = UUID.randomUUID().toString().replace("-", "");
  private static final String BUCKET = "vcut-test-" + UUID.randomUUID().toString().substring(0, 8);

  @Container
  static final GenericContainer<?> MINIO =
      new GenericContainer<>(IMAGE)
          .withExposedPorts(9000)
          .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
          .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
          .withEnv("MINIO_API_CORS_ALLOW_ORIGIN", "http://localhost:3000")
          .withCommand("server", "/tmp/data", "--address", ":9000")
          .waitingFor(Wait.forLogMessage(".*API:.*9000.*\\n", 1));

  private static S3Client client;
  private static S3Presigner presigner;
  private static S3CompatibleObjectStorageAdapter storage;

  @BeforeAll
  static void setUp() {
    String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    ObjectStorageProperties properties =
        new ObjectStorageProperties(
            true,
            endpoint,
            "us-east-1",
            BUCKET,
            ACCESS_KEY,
            SECRET_KEY,
            true,
            true,
            Duration.ofMinutes(5));
    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
    S3Configuration s3Configuration =
        S3Configuration.builder().pathStyleAccessEnabled(true).build();
    client =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials)
            .serviceConfiguration(s3Configuration)
            .build();
    presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials)
            .serviceConfiguration(s3Configuration)
            .build();
    storage = new S3CompatibleObjectStorageAdapter(client, presigner, properties);
  }

  @AfterAll
  static void tearDown() {
    client.close();
    presigner.close();
  }

  @Test
  void uploadsReadsHeadsAndDeletesThroughS3CompatibleContract() throws Exception {
    storage.ensureBucket();
    String objectKey = "users/user/projects/project/source/video/original.mp4";
    byte[] content = "video-data".getBytes();
    ObjectStorage.PresignedUpload upload =
        storage.presignUpload(objectKey, "video/mp4", content.length);

    HttpResponse<Void> preflight =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(upload.url()))
                    .header("Origin", "http://localhost:3000")
                    .header("Access-Control-Request-Method", "PUT")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding());

    assertThat(preflight.statusCode()).isIn(200, 204);
    assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin"))
        .contains("http://localhost:3000");

    HttpResponse<Void> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(upload.url()))
                    .header("Content-Type", "video/mp4")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build(),
                HttpResponse.BodyHandlers.discarding());

    assertThat(response.statusCode()).isEqualTo(200);
    ObjectStorage.StoredObject stored = storage.head(objectKey).orElseThrow();
    assertThat(stored.contentLength()).isEqualTo(content.length);
    assertThat(stored.contentType()).isEqualTo("video/mp4");
    assertThat(storage.read(objectKey).readAllBytes()).containsExactly(content);

    storage.delete(objectKey);
    assertThat(storage.head(objectKey)).isEmpty();
  }
}
