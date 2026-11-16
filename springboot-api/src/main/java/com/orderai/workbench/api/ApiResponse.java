package com.orderai.workbench.api;

import java.time.Instant;

// Shared response envelope keeps success and error payloads consistent for every client.
public record ApiResponse<T>(String status, String message, T data, Instant timestamp) {
  /** Builds a successful envelope: {@code status=success} with the payload in {@code data}. */
  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>("success", "Request completed", data, Instant.now());
  }

  /** Builds an error envelope: {@code status=error} with a human-readable {@code message}. */
  public static <T> ApiResponse<T> failure(String message) {
    return new ApiResponse<>("error", message, null, Instant.now());
  }
}
