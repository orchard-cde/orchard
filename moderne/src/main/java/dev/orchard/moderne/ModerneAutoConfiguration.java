package dev.orchard.moderne;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the Moderne integration module.
 * Creates the ModerneClient bean from the configuration properties.
 */
@Configuration
@EnableConfigurationProperties(ModerneConfig.class)
public class ModerneAutoConfiguration {

    @Bean
    public ModerneClient moderneClient(ModerneConfig config) {
        return new ModerneClient(config);
    }
}
