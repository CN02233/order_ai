package com.orderai.workbench.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Logs incoming API requests with their response status and elapsed time. */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    long startedAt = System.nanoTime();
    log.info("Incoming request: method={} path={}", request.getMethod(), request.getRequestURI());
    try {
      filterChain.doFilter(request, response);
    } finally {
      long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
      log.info("Request completed: method={} path={} status={} durationMs={}",
          request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMs);
    }
  }
}
