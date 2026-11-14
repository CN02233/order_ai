package com.orderai.workbench.controller;

import com.orderai.workbench.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
  @GetMapping("/api/health")
  /**
   * Returns the API health status.
   * @return the result of this operation
   */
  public ApiResponse<Map<String, String>> health() {
    return ApiResponse.success(Map.of("service", "order-ai-business-api"));
  }
}
