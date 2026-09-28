package com.vcut.api.shared.errors;

import org.springframework.http.HttpStatus;

public class DomainException extends ApiException {

  public DomainException(String message) {
    super("DOMAIN_ERROR", message, HttpStatus.BAD_REQUEST);
  }
}
