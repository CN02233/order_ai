package com.orderai.workbench.api;

import java.time.Instant;

// Shared response envelope keeps success and error payloads consistent for every client.
public record ApiResponse<T>(String status, String message, T data, Instant timestamp) {
  /**
   * Builds a successful API response envelope.
   * @param data input used by this operation
   * @return the result of this operation
   */
  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>("success", "Request completed", data, Instant.now());
  }

  /**
   * Builds an error API response envelope.
   * @param message input used by this operation
   * @return the result of this operation
   */
  public static <T> ApiResponse<T> failure(String message) {
    return new ApiResponse<>("error", message, null, Instant.now());
  }
}
