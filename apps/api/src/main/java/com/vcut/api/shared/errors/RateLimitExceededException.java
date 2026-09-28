package com.vcut.api.shared.errors;

import org.springframework.http.HttpStatus;

public class RateLimitExceededException extends ApiException {

  public RateLimitExceededException(String message) {
    super("RATE_LIMIT_EXCEEDED", message, HttpStatus.TOO_MANY_REQUESTS);
  }
}
