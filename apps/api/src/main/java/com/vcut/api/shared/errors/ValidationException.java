package com.vcut.api.shared.errors;

import org.springframework.http.HttpStatus;

public class ValidationException extends ApiException {

  public ValidationException(String message) {
    super("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
  }
}
