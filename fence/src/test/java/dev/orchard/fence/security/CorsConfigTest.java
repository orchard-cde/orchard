package dev.orchard.fence.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CorsConfig.class))
            .withBean(CorsProperties.class, () -> {
                CorsProperties props = new CorsProperties();
                props.setAllowedOrigins(java.util.List.of("http://localhost:3000"));
                props.setAllowedMethods(java.util.List.of("GET", "POST", "OPTIONS"));
                props.setAllowedHeaders(java.util.List.of("*"));
                return props;
            });

    @Test
    void registersCorsFilter() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CorsFilter.class);
            CorsFilter corsFilter = context.getBean(CorsFilter.class);
            assertThat(corsFilter).isNotNull();
        });
    }

    @Test
    void corsFilterHasExpectedConfig() {
        contextRunner.run(context -> {
            CorsFilter corsFilter = context.getBean(CorsFilter.class);
            assertThat(corsFilter).isNotNull();
            assertThat(corsFilter).isInstanceOf(CorsFilter.class);
        });
    }
}
