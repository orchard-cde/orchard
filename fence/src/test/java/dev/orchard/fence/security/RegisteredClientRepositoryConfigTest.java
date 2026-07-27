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
            .withPropertyValues(
                    "fence.client.client-id=orchard-ui",
                    "fence.client.redirect-uri=http://localhost:3000/callback"
            );

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
}
