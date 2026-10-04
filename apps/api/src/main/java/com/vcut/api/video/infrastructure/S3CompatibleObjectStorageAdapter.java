package com.vcut.api.video.infrastructure;

import com.vcut.api.shared.errors.ExternalProviderException;
import com.vcut.api.video.application.ExternalVideoImportStorage;
import com.vcut.api.video.application.ObjectStorage;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
@ConditionalOnProperty(prefix = "vcut.storage", name = "enabled", havingValue = "true")
public class S3CompatibleObjectStorageAdapter implements ObjectStorage, ExternalVideoImportStorage {

  private final S3Client client;
  private final S3Presigner presigner;
  private final ObjectStorageProperties properties;

  public S3CompatibleObjectStorageAdapter(
      S3Client client, S3Presigner presigner, ObjectStorageProperties properties) {
    this.client = client;
    this.presigner = presigner;
    this.properties = properties;
  }

  @Override
  public void ensureBucket() {
    try {
      client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
    } catch (S3Exception exception) {
      if (!isNotFound(exception) || !properties.autoCreateBucket()) {
        throw providerError("Unable to access object storage bucket", exception);
      }
      try {
        client.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
      } catch (S3Exception createException) {
        if (!isAlreadyExists(createException)) {
          throw providerError("Unable to create object storage bucket", createException);
        }
      }
    }
  }

  @Override
  public PresignedUpload presignUpload(String objectKey, String contentType, long contentLength) {
    PutObjectRequest putObjectRequest =
        PutObjectRequest.builder()
            .bucket(properties.bucket())
            .key(objectKey)
            .contentType(contentType)
            .contentLength(contentLength)
            .build();
    PutObjectPresignRequest presignRequest =
        PutObjectPresignRequest.builder()
            .signatureDuration(properties.presignedUrlTtl())
            .putObjectRequest(putObjectRequest)
            .build();
    var presigned = presigner.presignPutObject(presignRequest);
    return new PresignedUpload(
        presigned.url().toString(), Instant.now().plus(properties.presignedUrlTtl()));
  }

  @Override
  public PresignedDownload presignDownload(String objectKey) {
    var getObjectRequest =
        software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket(properties.bucket())
            .key(objectKey)
            .build();
    var presignRequest =
        GetObjectPresignRequest.builder()
            .signatureDuration(properties.presignedUrlTtl())
            .getObjectRequest(getObjectRequest)
            .build();
    var presigned = presigner.presignGetObject(presignRequest);
    return new PresignedDownload(
        presigned.url().toString(), Instant.now().plus(properties.presignedUrlTtl()));
  }

  @Override
  public Optional<StoredObject> head(String objectKey) {
    try {
      var response =
          client.headObject(
              HeadObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
      return Optional.of(
          new StoredObject(
              objectKey,
              response.contentLength(),
              response.contentType(),
              response.eTag(),
              response.checksumSHA256()));
    } catch (NoSuchKeyException exception) {
      return Optional.empty();
    } catch (S3Exception exception) {
      if (isNotFound(exception)) {
        return Optional.empty();
      }
      throw providerError("Unable to inspect object storage object", exception);
    }
  }

  @Override
  public InputStream read(String objectKey) {
    try {
      ResponseInputStream<?> response =
          client.getObject(
              software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                  .bucket(properties.bucket())
                  .key(objectKey)
                  .build());
      return response;
    } catch (S3Exception exception) {
      throw providerError("Unable to read object storage object", exception);
    }
  }

  @Override
  public StoredObject upload(
      Path source, String objectKey, String contentType, long contentLength) {
    if (source == null || !java.nio.file.Files.isRegularFile(source) || contentLength <= 0) {
      throw new IllegalArgumentException("import source file and content length are required");
    }
    try {
      client.putObject(
          PutObjectRequest.builder()
              .bucket(properties.bucket())
              .key(objectKey)
              .contentType(contentType)
              .contentLength(contentLength)
              .build(),
          RequestBody.fromFile(source));
      return head(objectKey)
          .orElseThrow(() -> new IllegalStateException("Imported object was not persisted."));
    } catch (S3Exception exception) {
      throw providerError("Unable to store imported video", exception);
    }
  }

  @Override
  public void delete(String objectKey) {
    try {
      client.deleteObject(
          software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
              .bucket(properties.bucket())
              .key(objectKey)
              .build());
    } catch (S3Exception exception) {
      throw providerError("Unable to delete object storage object", exception);
    }
  }

  private ExternalProviderException providerError(String message, Exception exception) {
    return new ExternalProviderException(message + ": " + exception.getClass().getSimpleName());
  }

  private static boolean isNotFound(S3Exception exception) {
    return exception.statusCode() == 404;
  }

  private static boolean isAlreadyExists(S3Exception exception) {
    return exception.statusCode() == 409
        || "BucketAlreadyOwnedByYou".equals(exception.awsErrorDetails().errorCode());
  }
}
