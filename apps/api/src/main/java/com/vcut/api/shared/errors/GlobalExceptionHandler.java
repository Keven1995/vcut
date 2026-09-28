package com.vcut.api.shared.errors;

import com.vcut.api.shared.api.ErrorResponse;
import com.vcut.api.shared.correlation.CorrelationContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
    return response(exception.status(), exception.code(), exception.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> handleValidationException(
      MethodArgumentNotValidException exception) {
    return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException exception) {
    return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request body is invalid.");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
    String traceId = traceId();
    LOGGER.error("Unhandled request exception traceId={}", traceId, exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ErrorResponse("INTERNAL_ERROR", "Unexpected error.", traceId));
  }

  private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message) {
    String traceId = traceId();
    return ResponseEntity.status(status).body(new ErrorResponse(code, message, traceId));
  }

  private String traceId() {
    return CorrelationContext.current()
        .map(UUID::toString)
        .orElseGet(() -> UUID.randomUUID().toString());
  }
}
