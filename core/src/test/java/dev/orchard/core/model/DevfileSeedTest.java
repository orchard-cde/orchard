package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DevfileSeedTest {

    @Test
    void builder_defaultsLifecycleCommandsToNull() {
        DevfileSeed seed = DevfileSeed.builder()
                .name("my-workspace")
                .image("quay.io/devfile/universal-developer-image:latest")
                .build();

        assertThat(seed.preStartCommand()).isNull();
        assertThat(seed.postStartCommand()).isNull();
    }

    @Test
    void builder_setsLifecycleCommands() {
        LifecycleCommand preStart = new LifecycleCommand.Sequential(List.of("echo pre-start"));
        LifecycleCommand postStart = new LifecycleCommand.Sequential(List.of("echo post-start"));

        DevfileSeed seed = DevfileSeed.builder()
                .name("my-workspace")
                .image("quay.io/devfile/universal-developer-image:latest")
                .preStartCommand(preStart)
                .postStartCommand(postStart)
                .build();

        assertThat(seed.preStartCommand()).isEqualTo(preStart);
        assertThat(seed.postStartCommand()).isEqualTo(postStart);
    }
}
