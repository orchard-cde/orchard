package dev.orchard.vine;

import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SshVineTest {

    @Test
    void commands_isAnSshRunnerAddressedFromThePlot() {
        UUID plotId = UUID.randomUUID();
        UUID groveId = UUID.randomUUID();
        VmPlot plot = new VmPlot(plotId, groveId, "p", "i-1", PlotState.SAPLING, Instant.now(),
            Instant.now(), "10.0.0.1", 2222, List.of(), VmSpec.small());

        CommandRunner runner = new SshVine(plot).commands();

        assertThat(runner).isInstanceOf(SshExecutor.class);
        SshExecutor ssh = (SshExecutor) runner;
        assertThat(ssh.host()).isEqualTo("10.0.0.1");
        assertThat(ssh.port()).isEqualTo(2222);
        assertThat(ssh.targetId()).isEqualTo(plotId).isNotEqualTo(groveId);
    }
}
