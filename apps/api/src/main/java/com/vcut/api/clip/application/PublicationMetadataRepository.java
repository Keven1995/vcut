package com.vcut.api.clip.application;

import com.vcut.api.clip.domain.PublicationMetadata;
import com.vcut.api.clip.domain.PublicationPlatform;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PublicationMetadataRepository {

  Optional<PublicationMetadata> find(UUID clipId, int editVersion, PublicationPlatform platform);

  List<PublicationMetadata> findAll(UUID clipId, int editVersion);

  PublicationMetadata save(PublicationMetadata metadata);

  PublicationMetadata update(PublicationMetadata metadata);
}
