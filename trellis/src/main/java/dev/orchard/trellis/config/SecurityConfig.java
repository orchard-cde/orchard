package dev.orchard.trellis.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security configuration for the Orchard platform.
 * <p>
 * When an OIDC issuer is configured (orchard.security.oauth2.enabled=true),
 * the secured filter chain validates JWT tokens on all /api/** endpoints
 * except health and actuator.
 * <p>
 * When no OIDC issuer is configured (default for dev), the permissive filter
 * chain allows all requests through without authentication.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${orchard.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Secured filter chain - active when orchard.security.oauth2.enabled=true.
     * Configures JWT resource server validation with:
     * - /api/health/** and /actuator/** open to all
     * - /api/** requiring authentication
     * - Stateless session management
     */
    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "orchard.security.oauth2.enabled", havingValue = "true")
    public SecurityFilterChain securedFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {})
            );

        return http.build();
    }

    /**
     * Permissive filter chain - active when orchard.security.oauth2.enabled is
     * not set or set to false. Allows all requests through for local development.
     */
    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "orchard.security.oauth2.enabled", havingValue = "false", matchIfMissing = true)
    public SecurityFilterChain permissiveFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            );

        return http.build();
    }
}
