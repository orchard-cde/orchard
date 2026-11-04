package dev.orchard.fence.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.orchard.fence.web.DeviceActivationController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2DeviceVerificationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration
@EnableWebSecurity
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            JWKSource<SecurityContext> jwkSource,
            RegisteredClientRepository registeredClientRepository,
            AuthorizationServerSettings authorizationServerSettings,
            ObjectProvider<StandaloneAuthenticationFilter> standaloneAuthenticationFilterProvider) throws Exception {

        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();

        // trowel-cli and orchard-ui are public clients (no client secret) and can't
        // authenticate via any of Spring Authorization Server's built-in methods; the device
        // authorization, device-code and refresh-token requests only ever carry a client_id.
        PublicClientAuthenticationConverter publicClientAuthenticationConverter =
                new PublicClientAuthenticationConverter(
                        authorizationServerSettings.getDeviceAuthorizationEndpoint(),
                        authorizationServerSettings.getTokenEndpoint());
        PublicClientAuthenticationProvider publicClientAuthenticationProvider =
                new PublicClientAuthenticationProvider(registeredClientRepository);

        authorizationServerConfigurer
                .clientAuthentication(clientAuthentication -> clientAuthentication
                        .authenticationConverter(publicClientAuthenticationConverter)
                        .authenticationProvider(publicClientAuthenticationProvider))
                .deviceAuthorizationEndpoint(deviceAuthorization -> deviceAuthorization
                        .verificationUri(DeviceActivationController.PATH))
                // Unlike the authorization_code flow, Spring's device-verification
                // provider ignores ClientSettings#isRequireAuthorizationConsent() and
                // always requires consent unless a prior consent record exists; wire it
                // up explicitly so trowel-cli's requireAuthorizationConsent(false) is honored.
                .deviceVerificationEndpoint(deviceVerification -> deviceVerification
                        .authenticationProviders(providers -> providers.forEach(provider -> {
                            if (provider instanceof OAuth2DeviceVerificationAuthenticationProvider deviceVerificationProvider) {
                                deviceVerificationProvider.setAuthorizationConsentRequired(context ->
                                        context.getRegisteredClient().getClientSettings().isRequireAuthorizationConsent());
                            }
                        })))
                .oidc(Customizer.withDefaults());

        http.securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .cors(Customizer.withDefaults())
                .with(authorizationServerConfigurer, Customizer.withDefaults())
                .authorizeHttpRequests(authorize ->
                        authorize.anyRequest().permitAll()
                );

        StandaloneAuthenticationFilter standaloneAuthenticationFilter = standaloneAuthenticationFilterProvider.getIfAvailable();
        if (standaloneAuthenticationFilter != null) {
            http.addFilterAfter(standaloneAuthenticationFilter, org.springframework.security.web.context.SecurityContextHolderFilter.class);
        }

        return http.build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(JWKSet jwkSet) {
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(FenceProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.getIssuer())
                .build();
    }
}
