package com.vcut.api.video.application;

import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

public interface ObjectStorage {

  void ensureBucket();

  PresignedUpload presignUpload(String objectKey, String contentType, long contentLength);

  PresignedDownload presignDownload(String objectKey);

  Optional<StoredObject> head(String objectKey);

  InputStream read(String objectKey);

  void delete(String objectKey);

  record PresignedUpload(String url, Instant expiresAt) {}

  record PresignedDownload(String url, Instant expiresAt) {}

  record StoredObject(
      String objectKey,
      long contentLength,
      String contentType,
      String eTag,
      String checksumSha256) {}
}
