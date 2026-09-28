package com.vcut.api.shared.messaging;

public final class MessageCompatibility {

  public static final int SUPPORTED_VERSION = 1;

  private MessageCompatibility() {}

  public static void requireSupported(MessageEnvelope message) {
    if (message.eventVersion() != SUPPORTED_VERSION || message.version() != SUPPORTED_VERSION) {
      throw new UnsupportedMessageVersionException(
          message.eventType(), message.eventVersion(), message.version());
    }
  }

  public static final class UnsupportedMessageVersionException extends IllegalArgumentException {

    public UnsupportedMessageVersionException(String eventType, int eventVersion, int version) {
      super(
          "Unsupported message version for "
              + eventType
              + ": eventVersion="
              + eventVersion
              + ", version="
              + version);
    }
  }
}
