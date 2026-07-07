package com.adpilot.common.api;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal Spring Boot configuration used as the context root for the servlet-level
 * JSON error-path slice tests in this package. It exists so that {@code @WebMvcTest}
 * does not fall back to the main {@code AdPilotApplication}, whose {@code @MapperScan}
 * would require a live datasource/SqlSessionFactory that is irrelevant to these
 * web-layer tests.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class ApiSliceTestApplication {
}
