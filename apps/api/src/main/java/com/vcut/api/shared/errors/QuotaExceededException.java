package com.vcut.api.shared.errors;

import org.springframework.http.HttpStatus;

public class QuotaExceededException extends ApiException {

  public QuotaExceededException(String message) {
    super("QUOTA_EXCEEDED", message, HttpStatus.TOO_MANY_REQUESTS);
  }
}
