package dev.orchard.nursery.container;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerConfigTest {

    private static ContainerConfig bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
            .bindOrCreate("orchard.nursery.container", ContainerConfig.class);
    }

    @Test
    void unset_defaultsWorkspaceRootUnderUserHomeAndNoEngineUrl() {
        ContainerConfig config = bind(Map.of());

        assertThat(config.workspaceRoot()).isEqualTo(Path.of(System.getProperty("user.home"), ".orchard", "plots"));
        assertThat(config.engineUrl()).isNull();
    }

    @Test
    void set_bindsBothProperties() {
        ContainerConfig config = bind(Map.of(
            "orchard.nursery.container.workspace-root", "/srv/plots",
            "orchard.nursery.container.engine-url", "tcp://engine:2375"));

        assertThat(config.workspaceRoot()).isEqualTo(Path.of("/srv/plots"));
        assertThat(config.engineUrl()).isEqualTo("tcp://engine:2375");
    }

    @Test
    void blankEngineUrl_meansTheEnvironmentsEngine() {
        assertThat(bind(Map.of("orchard.nursery.container.engine-url", "  ")).engineUrl()).isNull();
    }
}
