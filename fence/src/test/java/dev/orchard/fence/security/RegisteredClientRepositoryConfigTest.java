package dev.orchard.fence.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;

class RegisteredClientRepositoryConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RegisteredClientRepositoryConfig.class))
            .withBean(FenceClientProperties.class, () -> {
                FenceClientProperties props = new FenceClientProperties();
                props.setClientId("orchard-ui");
                props.setRedirectUri("http://localhost:3000/callback");
                return props;
            })
            .withBean(FenceGatewayClientProperties.class, () -> {
                FenceGatewayClientProperties props = new FenceGatewayClientProperties();
                props.setClientId("orchard-gateway");
                props.setClientSecret("dev-secret");
                return props;
            });

    @Test
    void trowelCliIsRegisteredForDeviceFlow() {
        contextRunner.run(context -> {
            RegisteredClientRepository repo = context.getBean(RegisteredClientRepository.class);
            RegisteredClient client = repo.findByClientId("trowel-cli");

            assertThat(client).isNotNull();
            assertThat(client.getClientAuthenticationMethods()).contains(ClientAuthenticationMethod.NONE);
            assertThat(client.getAuthorizationGrantTypes()).contains(
                    AuthorizationGrantType.DEVICE_CODE,
                    AuthorizationGrantType.REFRESH_TOKEN);
            assertThat(client.getScopes()).contains("openid");
        });
    }

    @Test
    void orchardUiIsRegisteredForAuthorizationCodeFlow() {
        contextRunner.run(context -> {
            RegisteredClientRepository repo = context.getBean(RegisteredClientRepository.class);
            RegisteredClient client = repo.findByClientId("orchard-ui");

            assertThat(client).isNotNull();
            assertThat(client.getClientAuthenticationMethods()).contains(ClientAuthenticationMethod.NONE);
            assertThat(client.getAuthorizationGrantTypes()).contains(
                    AuthorizationGrantType.AUTHORIZATION_CODE,
                    AuthorizationGrantType.REFRESH_TOKEN);
            assertThat(client.getScopes()).contains("openid", "profile", "email");
        });
    }

    @Test
    void orchardGatewayIsRegisteredForClientCredentials() {
        contextRunner.run(context -> {
            RegisteredClientRepository repo = context.getBean(RegisteredClientRepository.class);
            RegisteredClient client = repo.findByClientId("orchard-gateway");

            assertThat(client).isNotNull();
            assertThat(client.getClientAuthenticationMethods()).contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            assertThat(client.getAuthorizationGrantTypes()).contains(AuthorizationGrantType.CLIENT_CREDENTIALS);
            assertThat(client.getClientSecret()).startsWith("{noop}");
            assertThat(client.getClientSecret()).endsWith("dev-secret");
        });
    }
}
