package com.vcut.api.shared.errors;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends ApiException {

  public ResourceNotFoundException(String message) {
    super("RESOURCE_NOT_FOUND", message, HttpStatus.NOT_FOUND);
  }
}
