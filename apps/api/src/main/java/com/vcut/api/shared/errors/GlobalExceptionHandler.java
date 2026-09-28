package com.vcut.api.shared.errors;

import com.vcut.api.shared.api.ErrorResponse;
import com.vcut.api.shared.correlation.CorrelationContext;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
    return response(exception.status(), exception.code(), exception.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> handleValidationException(
      MethodArgumentNotValidException exception) {
    return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
    return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error.");
  }

  private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message) {
    String traceId =
        CorrelationContext.current()
            .map(UUID::toString)
            .orElseGet(() -> UUID.randomUUID().toString());
    return ResponseEntity.status(status).body(new ErrorResponse(code, message, traceId));
  }
}
