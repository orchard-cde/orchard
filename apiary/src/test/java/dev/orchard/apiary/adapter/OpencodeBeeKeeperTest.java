package dev.orchard.apiary.adapter;

import dev.orchard.apiary.BeeProvisioningException;
import dev.orchard.core.model.Bee;
import dev.orchard.core.model.BeeHealth;
import dev.orchard.core.model.BeeSpec;
import dev.orchard.core.model.BeeType;
import dev.orchard.nursery.CommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpencodeBeeKeeperTest {

    @Mock private CommandRunner runner;

    private OpencodeBeeKeeper keeper;
    private final UUID groveId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        keeper = new OpencodeBeeKeeper();
    }

    @Test
    void getBeeType_returnsOpencode() {
        assertThat(keeper.getBeeType()).isEqualTo(BeeType.OPENCODE);
    }

    @Test
    void prerequisites_listsCurlAndGit() {
        assertThat(keeper.prerequisites()).containsKeys("curl", "git");
    }

    @Test
    void install_binaryAlreadyPresent_skipsInstallScript() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        when(runner.execute("command -v opencode")).thenReturn("/usr/local/bin/opencode");

        Bee result = keeper.install(bee, bee.spec(), runner).join();

        assertThat(result).isEqualTo(bee);
        verify(runner, never()).execute(contains("curl"));
    }

    @Test
    void install_binaryMissing_runsInstallScript() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        when(runner.execute("command -v opencode")).thenThrow(new IOException("not found"));
        when(runner.execute(contains("curl"))).thenReturn("");

        keeper.install(bee, bee.spec(), runner).join();

        verify(runner).execute(contains("install.sh"));
    }

    @Test
    void install_writesConfigWithGroveAndBeeIds() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        when(runner.execute("command -v opencode")).thenReturn("/usr/local/bin/opencode");

        keeper.install(bee, bee.spec(), runner).join();

        verify(runner).execute(argThat(cmd ->
            cmd.contains("mkdir -p /workspace/.opencode") && cmd.contains("opencode.jsonc")));
    }

    @Test
    void install_commandFailure_wrapsInBeeProvisioningException() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        when(runner.execute("command -v opencode")).thenReturn("/usr/local/bin/opencode");
        when(runner.execute(contains("mkdir -p"))).thenThrow(new IOException("disk full"));

        assertThatThrownBy(() -> keeper.install(bee, bee.spec(), runner).join())
            .hasCauseInstanceOf(BeeProvisioningException.class);
    }

    @Test
    void release_startsProcessAndCapturesPid() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        when(runner.execute(contains("nohup opencode serve"))).thenReturn("12345\n");

        Bee result = keeper.release(bee, runner).join();

        assertThat(result.processId()).isEqualTo("12345");
    }

    @Test
    void release_interactiveMode_skipsProcessStart() throws Exception {
        BeeSpec spec = new BeeSpec(BeeType.OPENCODE, null, Map.of("mode", "interactive"));
        Bee bee = Bee.hatching(groveId, spec);

        Bee result = keeper.release(bee, runner).join();

        assertThat(result.processId()).isNull();
        verify(runner, never()).execute(contains("nohup"));
    }

    @Test
    void smoke_withProcessId_sendsKillSignal() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE)).withProcessId("12345");
        when(runner.execute("kill 12345")).thenReturn("");

        keeper.smoke(bee, runner).join();

        verify(runner).execute("kill 12345");
    }

    @Test
    void smoke_withoutProcessId_isNoOp() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));

        keeper.smoke(bee, runner).join();

        verify(runner, never()).execute(contains("kill"));
    }

    @Test
    void inspect_processAliveAndResponsive_returnsHealthy() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE)).withProcessId("12345");
        when(runner.execute("kill -0 12345")).thenReturn("");
        when(runner.execute(eq("opencode --version"), anyLong())).thenReturn("1.0.0");

        BeeHealth health = keeper.inspect(bee, runner).join();

        assertThat(health.alive()).isTrue();
        assertThat(health.responsive()).isTrue();
    }

    @Test
    void inspect_noProcessId_returnsNotAlive() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));

        BeeHealth health = keeper.inspect(bee, runner).join();

        assertThat(health.alive()).isFalse();
        assertThat(health.responsive()).isFalse();
    }
}
