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
    String path = request.getRequestURI();
    boolean loggable = !isStaticResource(path);
    if (loggable) {
      log.info("Incoming request: method={} path={}", request.getMethod(), path);
    }
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (loggable) {
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info(
            "Request completed: method={} path={} status={} durationMs={}",
            request.getMethod(),
            path,
            response.getStatus(),
            elapsedMs);
      }
    }
  }

  /** Browser-only assets (favicon, css, js, images) are not API traffic and add log noise. */
  private static boolean isStaticResource(String path) {
    return path.equals("/favicon.ico")
        || path.matches(".*\\.(css|js|png|jpe?g|gif|svg|ico|woff2?|ttf|map)$");
  }
}
