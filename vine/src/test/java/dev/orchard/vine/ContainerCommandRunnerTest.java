package dev.orchard.vine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerCommandRunnerTest {

    @Test
    void unset_inheritsEnvironment() throws Exception {
        String inherited = Objects.requireNonNullElse(System.getenv("DOCKER_HOST"), "");

        assertThat(new ContainerCommandRunner(null).execute("printenv DOCKER_HOST || true").trim())
            .isEqualTo(inherited);
        assertThat(new ContainerCommandRunner(" ").execute("printenv DOCKER_HOST || true").trim())
            .isEqualTo(inherited);
        assertThat(new ContainerCommandRunner(null).engineEnv()).isEmpty();
    }

    @Test
    void unixUrl_setsBothVariables() throws Exception {
        assertSetsBoth("unix:///tmp/orchard-test.sock");
    }

    @Test
    void tcpUrl_setsBothVariables() throws Exception {
        assertSetsBoth("tcp://127.0.0.1:2375");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ssh://user@host", "http://127.0.0.1:2375"})
    void otherScheme_rejected(String url) {
        assertThatThrownBy(() -> new ContainerCommandRunner(url))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("unsupported container engine URL: " + url + "; use unix:// or tcp://");
    }

    private static void assertSetsBoth(String url) throws Exception {
        ContainerCommandRunner runner = new ContainerCommandRunner(url);

        assertThat(runner.execute("printenv DOCKER_HOST").trim()).isEqualTo(url);
        assertThat(runner.execute("printenv CONTAINER_HOST").trim()).isEqualTo(url);
        assertThat(runner.engineEnv()).isEqualTo(Map.of("DOCKER_HOST", url, "CONTAINER_HOST", url));
    }
}
