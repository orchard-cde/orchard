package dev.orchard.fence.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;

class RegisteredClientRepositoryConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RegisteredClientRepositoryConfig.class))
            .withBean(FenceClientProperties.class, () -> {
                FenceClientProperties props = new FenceClientProperties();
                props.setClientId("orchard-ui");
                props.setClientSecret("test-secret");
                props.setRedirectUri("http://localhost:3000/callback");
                return props;
            })
            .withPropertyValues(
                    "fence.client.client-id=orchard-ui",
                    "fence.client.client-secret=test-secret",
                    "fence.client.redirect-uri=http://localhost:3000/callback"
            );

    @Test
    void registersStaticClient() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RegisteredClientRepository.class);
            RegisteredClientRepository repo = context.getBean(RegisteredClientRepository.class);
            RegisteredClient client = repo.findByClientId("orchard-ui");
            assertThat(client).isNotNull();
            assertThat(client.getClientId()).isEqualTo("orchard-ui");
        });
    }

    @Test
    void clientSupportsDeviceCodeGrant() {
        contextRunner.run(context -> {
            RegisteredClientRepository repo = context.getBean(RegisteredClientRepository.class);
            RegisteredClient client = repo.findByClientId("orchard-ui");
            assertThat(client.getAuthorizationGrantTypes())
                    .contains(AuthorizationGrantType.DEVICE_CODE);
        });
    }
}
