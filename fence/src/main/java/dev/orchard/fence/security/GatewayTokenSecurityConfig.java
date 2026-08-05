package dev.orchard.fence.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Validates bearer tokens presented to /gateway-token locally against fence's
 * own JWKSet (the tokens were issued by fence itself). Ordered @Order(0), ahead
 * of the OAuth2 authorization-server chain (@Order(1)), so /gateway-token never
 * falls through to the interactive endpoints matcher.
 */
@Configuration
public class GatewayTokenSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain gatewayTokenSecurityFilterChain(
            HttpSecurity http, JwtDecoder gatewayTokenJwtDecoder) throws Exception {
        http
                .securityMatchers(matchers -> matchers.requestMatchers("/gateway-token"))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(gatewayTokenJwtDecoder)));

        return http.build();
    }

    @Bean
    JwtDecoder gatewayTokenJwtDecoder(JWKSet jwkSet, FenceProperties properties) {
        JWSKeySelector<SecurityContext> selector =
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, new ImmutableJWKSet<>(jwkSet));
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(selector);
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuer()));
        return decoder;
    }

    /**
     * StandaloneAuthenticationFilter is a {@code @Component}, so Spring Boot also
     * auto-registers it as a plain servlet filter with default (lowest-precedence)
     * order, applied to every request outside of Spring Security's FilterChainProxy.
     * That global pass runs *after* all ordered SecurityFilterChains — including this
     * class's @Order(0) chain — and unconditionally overwrites SecurityContextHolder
     * with the fixed standalone principal, wiping out the JwtAuthenticationToken that
     * gatewayTokenSecurityFilterChain just established for /gateway-token before the
     * controller's @AuthenticationPrincipal Jwt argument can resolve it. The filter is
     * already wired explicitly into the chains that need it (AuthorizationServerConfig,
     * StandaloneSecurityConfig), so disable the redundant global auto-registration.
     */
    @Bean
    @ConditionalOnBean(StandaloneAuthenticationFilter.class)
    FilterRegistrationBean<StandaloneAuthenticationFilter> standaloneAuthenticationFilterRegistration(
            StandaloneAuthenticationFilter standaloneAuthenticationFilter) {
        FilterRegistrationBean<StandaloneAuthenticationFilter> registration =
                new FilterRegistrationBean<>(standaloneAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }
}
