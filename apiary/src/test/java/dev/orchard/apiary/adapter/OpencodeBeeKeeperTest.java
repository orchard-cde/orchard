package dev.orchard.apiary.adapter;

import dev.orchard.apiary.BeeProvisioningException;
import dev.orchard.core.model.Bee;
import dev.orchard.core.model.BeeHealth;
import dev.orchard.core.model.BeeSpec;
import dev.orchard.core.model.BeeType;
import dev.orchard.vine.CommandRunner;
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

    private static final String ROOT = "/workspace";
    private static final String BINARY = "/home/dev/.opencode/bin/opencode";

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
        when(runner.execute(contains("command -v opencode"))).thenReturn("/usr/local/bin/opencode");

        Bee result = keeper.install(bee, bee.spec(), runner, ROOT).join();

        assertThat(result).isEqualTo(bee);
        verify(runner, never()).execute(contains("curl"));
    }

    @Test
    void install_binaryMissing_runsInstallScript() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        when(runner.execute(contains("command -v opencode")))
            .thenThrow(new IOException("not found")).thenReturn(BINARY);
        when(runner.execute(contains("curl"))).thenReturn("");

        keeper.install(bee, bee.spec(), runner, ROOT).join();

        verify(runner).execute(argThat(cmd -> cmd.contains("https://opencode.ai/install")
            && cmd.contains("-o /tmp/opencode-install.sh") && cmd.contains("&& bash /tmp/opencode-install.sh")));
    }

    @Test
    void install_installerFails_throws() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        when(runner.execute(contains("command -v opencode"))).thenThrow(new IOException("not found"));
        when(runner.execute(contains("curl"))).thenThrow(new IOException("curl: (22) 404"));

        assertThatThrownBy(() -> keeper.install(bee, bee.spec(), runner, ROOT).join())
            .hasCauseInstanceOf(BeeProvisioningException.class);
        verify(runner, never()).execute(contains("mkdir -p"));
    }

    @Test
    void install_binaryStillMissingAfterInstall_throws() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        when(runner.execute(contains("command -v opencode"))).thenThrow(new IOException("not found"));
        when(runner.execute(contains("curl"))).thenReturn("");

        assertThatThrownBy(() -> keeper.install(bee, bee.spec(), runner, ROOT).join())
            .hasCauseInstanceOf(BeeProvisioningException.class);
    }

    @Test
    void install_writesConfigWithGroveAndBeeIds() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        when(runner.execute(contains("command -v opencode"))).thenReturn("/usr/local/bin/opencode");

        keeper.install(bee, bee.spec(), runner, ROOT).join();

        verify(runner).execute(argThat(cmd ->
            cmd.contains("mkdir -p '/workspace/.opencode'") && cmd.contains("opencode.jsonc")));
    }

    @Test
    void install_writesConfigUnderWorkspaceRoot() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        when(runner.execute(contains("command -v opencode"))).thenReturn("/usr/local/bin/opencode");

        keeper.install(bee, bee.spec(), runner, "/workspaces/repo").join();

        verify(runner).execute(argThat(cmd ->
            cmd.contains("mkdir -p '/workspaces/repo/.opencode'")
                && cmd.contains("> '/workspaces/repo/.opencode/opencode.jsonc'")));
    }

    @Test
    void release_cdsIntoWorkspaceRoot() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        stubLaunch("1\n");

        keeper.release(bee, runner, "/workspaces/my repo").join();

        verify(runner).execute(startsWith("cd '/workspaces/my repo' && nohup '" + BINARY + "' serve"));
    }

    @Test
    void install_commandFailure_wrapsInBeeProvisioningException() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        when(runner.execute(contains("command -v opencode"))).thenReturn("/usr/local/bin/opencode");
        when(runner.execute(contains("mkdir -p"))).thenThrow(new IOException("disk full"));

        assertThatThrownBy(() -> keeper.install(bee, bee.spec(), runner, ROOT).join())
            .hasCauseInstanceOf(BeeProvisioningException.class);
    }

    @Test
    void release_startsProcessAndCapturesPid() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        stubLaunch("12345\n");

        Bee result = keeper.release(bee, runner, ROOT).join();

        assertThat(result.processId()).isEqualTo("12345");
        verify(runner).execute(argThat(cmd -> cmd.contains("nohup '" + BINARY + "' serve")));
        verify(runner).execute(argThat(cmd -> cmd.contains("curl") && cmd.contains("kill -0 12345")), anyLong());
    }

    @Test
    void release_serverNeverStarts_throwsWithLogTail() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        stubLaunch("12345\n");
        when(runner.execute(contains("curl -sS"), anyLong())).thenThrow(new IOException("exit 1"));
        when(runner.execute(startsWith("tail -n"))).thenReturn("boom: cannot bind\n");

        assertThatThrownBy(() -> keeper.release(bee, runner, ROOT).join())
            .hasCauseInstanceOf(BeeProvisioningException.class)
            .hasMessageContaining("boom: cannot bind");
    }

    @Test
    void release_binaryNotFound_throws() throws Exception {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.OPENCODE));
        when(runner.execute(contains("command -v opencode"))).thenThrow(new IOException("not found"));

        assertThatThrownBy(() -> keeper.release(bee, runner, ROOT).join())
            .hasCauseInstanceOf(BeeProvisioningException.class);
        verify(runner, never()).execute(contains("nohup"));
    }

    private void stubLaunch(String pid) throws Exception {
        when(runner.execute(contains("command -v opencode"))).thenReturn(BINARY + "\n");
        when(runner.execute(contains("nohup"))).thenReturn(pid);
    }

    @Test
    void release_interactiveMode_skipsProcessStart() throws Exception {
        BeeSpec spec = new BeeSpec(BeeType.OPENCODE, null, Map.of("mode", "interactive"));
        Bee bee = Bee.hatching(groveId, spec);

        Bee result = keeper.release(bee, runner, ROOT).join();

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
        when(runner.execute(contains("command -v opencode"))).thenReturn(BINARY);
        when(runner.execute(eq("'" + BINARY + "' --version"), anyLong())).thenReturn("1.0.0");

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

    @Test
    void renderConfig_isPackagePrivateAndEmbedsGroveAndBeeIds() throws Exception {
        Bee bee = Bee.hatching(groveId, new BeeSpec(BeeType.OPENCODE, "1.0.0",
            Map.of("theme", "dark")));

        String json = keeper.renderConfig(bee, bee.spec(), ROOT);

        assertThat(json)
            .contains("\"workspace_root\" : \"/workspace\"")
            .contains(bee.groveId().toString())
            .contains(bee.id().toString())
            .contains("\"theme\" : \"dark\"");
    }
}
