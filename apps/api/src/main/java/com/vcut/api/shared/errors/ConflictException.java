package com.vcut.api.shared.errors;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

  public ConflictException(String message) {
    super("RESOURCE_CONFLICT", message, HttpStatus.CONFLICT);
  }
}
