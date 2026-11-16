package com.orderai.workbench.controller;

import com.orderai.workbench.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
  /** Returns the API health status. */
  @GetMapping("/api/health")
  public ApiResponse<Map<String, String>> health() {
    return ApiResponse.success(Map.of("service", "order-ai-business-api"));
  }
}
