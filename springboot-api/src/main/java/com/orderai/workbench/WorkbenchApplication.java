package com.orderai.workbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

@SpringBootApplication
// Serialize pages as a stable DTO ({content, page}) instead of raw PageImpl internals.
@EnableSpringDataWebSupport(
    pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class WorkbenchApplication {
  /** Starts the Spring Boot application. */
  public static void main(String[] args) {
    SpringApplication.run(WorkbenchApplication.class, args);
  }
}
