package com.vcut.api.usage.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RetainedObject(
    UUID id,
    UUID userId,
    UUID projectId,
    String objectKey,
    RetentionAssetType assetType,
    long sizeBytes,
    RetentionStatus status,
    Instant expiresAt,
    int deleteAttempts,
    String lastFailureCode,
    Instant createdAt,
    Instant deletedAt) {

  public RetainedObject {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(projectId, "projectId");
    if (objectKey == null || objectKey.isBlank() || objectKey.length() > 512) {
      throw new IllegalArgumentException("objectKey must contain 1-512 characters");
    }
    Objects.requireNonNull(assetType, "assetType");
    if (sizeBytes < 0 || deleteAttempts < 0) {
      throw new IllegalArgumentException("retained object size and attempts must not be negative");
    }
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(createdAt, "createdAt");
    if (status == RetentionStatus.DELETED && deletedAt == null) {
      throw new IllegalArgumentException("deleted retained object requires deletedAt");
    }
    if (status != RetentionStatus.DELETED && deletedAt != null) {
      throw new IllegalArgumentException("active retained object must not have deletedAt");
    }
  }

  public RetainedObject updateSize(long newSizeBytes) {
    return new RetainedObject(
        id,
        userId,
        projectId,
        objectKey,
        assetType,
        newSizeBytes,
        status,
        expiresAt,
        deleteAttempts,
        lastFailureCode,
        createdAt,
        deletedAt);
  }

  public RetainedObject withExpiration(Instant newExpiresAt) {
    if (status != RetentionStatus.RETAINED) {
      throw new IllegalStateException("only retained objects can have their expiration changed");
    }
    return new RetainedObject(
        id,
        userId,
        projectId,
        objectKey,
        assetType,
        sizeBytes,
        status,
        newExpiresAt,
        deleteAttempts,
        lastFailureCode,
        createdAt,
        deletedAt);
  }

  public RetainedObject markDeletePending() {
    return new RetainedObject(
        id,
        userId,
        projectId,
        objectKey,
        assetType,
        sizeBytes,
        RetentionStatus.DELETE_PENDING,
        expiresAt,
        deleteAttempts + 1,
        null,
        createdAt,
        null);
  }

  public RetainedObject markDeleted(Instant now) {
    return new RetainedObject(
        id,
        userId,
        projectId,
        objectKey,
        assetType,
        sizeBytes,
        RetentionStatus.DELETED,
        expiresAt,
        deleteAttempts,
        null,
        createdAt,
        now);
  }

  public RetainedObject failed(String failureCode) {
    return new RetainedObject(
        id,
        userId,
        projectId,
        objectKey,
        assetType,
        sizeBytes,
        RetentionStatus.DELETE_PENDING,
        expiresAt,
        deleteAttempts,
        failureCode,
        createdAt,
        null);
  }
}
