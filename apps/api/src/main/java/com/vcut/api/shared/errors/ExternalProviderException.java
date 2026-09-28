package com.vcut.api.shared.errors;

import org.springframework.http.HttpStatus;

public class ExternalProviderException extends ApiException {

  public ExternalProviderException(String message) {
    super("EXTERNAL_PROVIDER_ERROR", message, HttpStatus.BAD_GATEWAY);
  }
}
