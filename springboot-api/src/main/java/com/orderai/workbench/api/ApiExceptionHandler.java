package com.orderai.workbench.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ResponseStatusException.class)
  /**
   * Converts a status exception into a standard API error response.
   * @param exception input used by this operation
   * @return the result of this operation
   */
  public ResponseEntity<ApiResponse<Void>> handleStatus(ResponseStatusException exception) {
    HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
    return ResponseEntity.status(status)
        .body(ApiResponse.failure(exception.getReason() == null ? "Request failed" : exception.getReason()));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  /**
   * Returns a standard error response for a missing required header.
   * @param exception input used by this operation
   * @return the result of this operation
   */
  public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException exception) {
    return ResponseEntity.badRequest().body(ApiResponse.failure("Missing required header: " + exception.getHeaderName()));
  }

  @ExceptionHandler(Exception.class)
  /**
   * Returns a safe generic error response for an unexpected exception.
   * @param exception input used by this operation
   * @return the result of this operation
   */
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
    log.error("Unhandled API exception", exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.failure("An unexpected server error occurred"));
  }
}
