package com.vcut.api.shared.errors;

import org.springframework.http.HttpStatus;

public class ProcessingException extends ApiException {

  public ProcessingException(String message) {
    super("PROCESSING_ERROR", message, HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
