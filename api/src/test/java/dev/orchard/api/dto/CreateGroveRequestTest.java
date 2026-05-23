package dev.orchard.api.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateGroveRequestTest {

    @Test
    void branch_defaultsToMainWhenNull() {
        var request = new CreateGroveRequest("https://github.com/user/repo", null, null, null, null);
        assertThat(request.branch()).isEqualTo("main");
    }

    @Test
    void branch_returnsProvidedValue() {
        var request = new CreateGroveRequest("https://github.com/user/repo", "develop", null, null, null);
        assertThat(request.branch()).isEqualTo("develop");
    }

    @Test
    void machineSize_defaultsToSmallWhenNull() {
        var request = new CreateGroveRequest("https://github.com/user/repo", null, null, null, null);
        assertThat(request.machineSize()).isEqualTo("small");
    }

    @Test
    void machineSize_returnsProvidedValue() {
        var request = new CreateGroveRequest("https://github.com/user/repo", null, null, "large", null);
        assertThat(request.machineSize()).isEqualTo("large");
    }
}
