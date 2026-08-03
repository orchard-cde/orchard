package dev.orchard.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayPropertiesTest {

    @org.springframework.context.annotation.Configuration
    @EnableConfigurationProperties(GatewayProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void defaultsMatchSpecTable() {
        contextRunner.run(context -> {
            GatewayProperties props = context.getBean(GatewayProperties.class);
            assertThat(props.getSshPort()).isEqualTo(2222);
            assertThat(props.getHostKeyPath()).endsWith("/.orchard/gateway-host-key");
            assertThat(props.getInternalSshKeyPath()).endsWith("/.ssh/orchard_ed25519");
            assertThat(props.getFence().getIssuerUri()).isEqualTo("http://localhost:7779");
            assertThat(props.getOauth2().getClientId()).isEqualTo("orchard-gateway");
            assertThat(props.getTrellis().getBaseUrl()).isEqualTo("http://localhost:8080");
        });
    }

    @Test
    void envVarsOverrideDefaults() {
        contextRunner
                .withPropertyValues("orchard.gateway.ssh-port=2300")
                .withPropertyValues("orchard.gateway.trellis.base-url=http://trellis:8080")
                .withPropertyValues("orchard.gateway.oauth2.client-secret=s3cret")
                .run(context -> {
                    GatewayProperties props = context.getBean(GatewayProperties.class);
                    assertThat(props.getSshPort()).isEqualTo(2300);
                    assertThat(props.getTrellis().getBaseUrl()).isEqualTo("http://trellis:8080");
                    assertThat(props.getOauth2().getClientSecret()).isEqualTo("s3cret");
                });
    }
}
