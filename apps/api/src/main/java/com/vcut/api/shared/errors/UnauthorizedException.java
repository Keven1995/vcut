package com.vcut.api.shared.errors;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends ApiException {

  public UnauthorizedException(String message) {
    super("AUTHENTICATION_REQUIRED", message, HttpStatus.UNAUTHORIZED);
  }
}
