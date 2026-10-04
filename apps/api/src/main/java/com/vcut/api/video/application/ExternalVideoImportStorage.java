package com.vcut.api.video.application;

import java.nio.file.Path;

/** Owner-scoped object storage write port used after an authorized import completes. */
public interface ExternalVideoImportStorage {

  void ensureBucket();

  ObjectStorage.StoredObject upload(
      Path source, String objectKey, String contentType, long contentLength);

  void delete(String objectKey);
}
