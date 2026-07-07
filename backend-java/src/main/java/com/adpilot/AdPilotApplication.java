package com.adpilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AdPilot AI - Main Application Entry Point
 *
 * <p>This is the bootstrap class for the AdPilot intelligent advertising
 * campaign management platform. It initializes the Spring application
 * context, auto-configures all components, and starts the embedded
 * web server.</p>
 *
 * <p>Key features enabled by {@code @SpringBootApplication}:</p>
 * <ul>
 *   <li>Component scanning under {@code com.adpilot} package</li>
 *   <li>Auto-configuration (Spring Boot, JPA, Security, Redis, etc.)</li>
 *   <li>Configuration properties binding</li>
 * </ul>
 *
 * @author AdPilot Team
 * @since 1.0.0
 */
@SpringBootApplication
@EnableScheduling
@MapperScan(basePackages = {"com.adpilot.modules.*.mapper", "com.adpilot.modules.advertising.hosting"},
        annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class AdPilotApplication {

    /**
     * Application entry point.
     *
     * @param args command-line arguments passed at startup
     */
    public static void main(String[] args) {
        SpringApplication.run(AdPilotApplication.class, args);
    }
}
