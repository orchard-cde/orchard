package dev.orchard.fence.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@ConditionalOnProperty(name = "fence.standalone.enabled", havingValue = "true")
public class StandaloneSecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain standaloneSecurityFilterChain(
            HttpSecurity http,
            StandaloneAuthenticationFilter standaloneAuthenticationFilter) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .addFilterBefore(standaloneAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());

        return http.build();
    }
}
