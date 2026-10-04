package com.vcut.api.video.application;

import com.vcut.api.shared.errors.ConflictException;
import com.vcut.api.shared.errors.ResourceNotFoundException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** In-process cooperative cancellation for active import attempts. */
@Component
public class VideoImportCancellationRegistry {

  private final ConcurrentHashMap<UUID, ActiveImport> activeImports = new ConcurrentHashMap<>();

  public Registration register(UUID importId, UUID userId) {
    ActiveImport active = new ActiveImport(userId, new AtomicBoolean());
    if (activeImports.putIfAbsent(importId, active) != null) {
      throw new ConflictException("This import request is already active.");
    }
    return new Registration(importId, active);
  }

  public void cancel(UUID importId, UUID userId) {
    ActiveImport active = activeImports.get(importId);
    if (active == null || !active.userId().equals(userId)) {
      throw new ResourceNotFoundException("Active import not found.");
    }
    active.cancelled().set(true);
  }

  public boolean isCancelled(UUID importId) {
    ActiveImport active = activeImports.get(importId);
    return active == null || active.cancelled().get();
  }

  public final class Registration implements AutoCloseable {
    private final UUID importId;
    private final ActiveImport active;

    private Registration(UUID importId, ActiveImport active) {
      this.importId = importId;
      this.active = active;
    }

    public boolean isCancelled() {
      return active.cancelled().get();
    }

    @Override
    public void close() {
      activeImports.remove(importId, active);
    }
  }

  private record ActiveImport(UUID userId, AtomicBoolean cancelled) {}
}
