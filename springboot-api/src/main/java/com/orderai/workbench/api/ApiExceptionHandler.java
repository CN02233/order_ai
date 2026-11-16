package com.orderai.workbench.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Normalizes API errors and makes every rejected request explain itself in the server log. */
@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private final HttpServletRequest request;

  public ApiExceptionHandler(HttpServletRequest request) {
    this.request = request;
  }

  /** Turns a deliberate status exception into the standard error envelope and logs it as a warning. */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> handleStatus(ResponseStatusException exception) {
    HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
    String reason = exception.getReason() == null ? "Request failed" : exception.getReason();
    log.warn("Rejected {} {} with {}: {}", request.getMethod(), request.getRequestURI(), status.value(), reason);
    return ResponseEntity.status(status).body(ApiResponse.failure(reason));
  }

  /** Answers a missing {@code Idempotency-Key} header with 400 and names the header. */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException exception) {
    String reason = "Missing required header: " + exception.getHeaderName();
    log.warn("Rejected {} {} with 400: {}", request.getMethod(), request.getRequestURI(), reason);
    return ResponseEntity.badRequest().body(ApiResponse.failure(reason));
  }

  /** Malformed JSON used to fall through to the generic 500 handler. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
    log.warn("Rejected {} {} with 400: malformed JSON body: {}", request.getMethod(), request.getRequestURI(),
        exception.getMostSpecificCause().getMessage());
    return ResponseEntity.badRequest().body(ApiResponse.failure("Malformed JSON request body"));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
    String reason = "Invalid value for parameter " + exception.getName() + ": " + exception.getValue();
    log.warn("Rejected {} {} with 400: {}", request.getMethod(), request.getRequestURI(), reason);
    return ResponseEntity.badRequest().body(ApiResponse.failure(reason));
  }

  /**
   * Missing static resources such as /favicon.ico are a normal 404 for an API-only server.
   * Without this handler the generic Exception handler turns them into a noisy 500 with a full stack trace.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Void> handleNoResource(NoResourceFoundException exception) {
    return ResponseEntity.notFound().build();
  }

  /** Last resort: logs the stack trace and returns a safe message without leaking internals. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
    log.error("Unhandled API exception on {} {}", request.getMethod(), request.getRequestURI(), exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.failure("An unexpected server error occurred"));
  }
}
