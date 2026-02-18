package dev.orchard.greenhouse.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the Greenhouse module.
 * Enables binding of orchard.greenhouse.* properties to GreenhouseConfig.
 */
@Configuration
@EnableConfigurationProperties(GreenhouseConfig.class)
public class GreenhouseAutoConfiguration {
}
