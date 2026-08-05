package dev.orchard.fence.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.util.UUID;

@Configuration
public class RegisteredClientRepositoryConfig {

    @Bean
    RegisteredClientRepository registeredClientRepository(
            FenceClientProperties clientProperties,
            FenceGatewayClientProperties gatewayClientProperties) {

        // Both clients are public (no client secret): trowel-cli is a CLI that can't
        // safely hold a secret, and orchard-ui runs entirely in the browser. Neither
        // can authenticate with CLIENT_SECRET_BASIC, so both use NONE.
        RegisteredClient trowelCli = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("trowel-cli")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.DEVICE_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope("openid")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .build())
                .build();

        RegisteredClient orchardUi = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientProperties.getClientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(clientProperties.getRedirectUri())
                .scope("openid")
                .scope("profile")
                .scope("email")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .build())
                .build();

        // Confidential client: the SSH gateway authenticates with client_id +
        // client_secret (CLIENT_SECRET_BASIC) to mint its own client_credentials
        // service token for trellis /api/gateway/** calls. The secret must equal
        // the gateway's own GATEWAY_OAUTH2_CLIENT_SECRET in deployment.
        RegisteredClient orchardGateway = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(gatewayClientProperties.getClientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientSecret("{noop}" + gatewayClientProperties.getClientSecret())
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("openid")
                .scope("gateway")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(trowelCli, orchardUi, orchardGateway);
    }
}
