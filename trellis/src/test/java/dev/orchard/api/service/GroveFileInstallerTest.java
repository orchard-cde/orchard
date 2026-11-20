package dev.orchard.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.orchard.api.service.GroveFileInstaller.FileSpec;
import dev.orchard.api.service.GroveFileInstaller.Target;
import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.LocalCommandRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class GroveFileInstallerTest {

    private static final byte[] CONTENT = "TOKEN='hunter2'\n".getBytes(StandardCharsets.UTF_8);

    private final GroveFileInstaller installer = new GroveFileInstaller();
    private final GroveProvider provider = mock(GroveProvider.class);
    private final CommandRunner runner = mock(CommandRunner.class);

    private void wire() {
        when(provider.host(any())).thenReturn(runner);
        when(provider.runtime(any())).thenReturn(ContainerRuntime.DOCKER);
    }

    @Test
    void install_fruit_execsAsRootWithStdin() throws Exception {
        wire();
        FileSpec spec = new FileSpec(GroveFileInstaller.FRUIT_SECRETS_PATH, "vscode", "vscode", "0400", CONTENT);

        installer.install(provider, null, "abc123", Target.FRUIT, spec);

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> stdin = ArgumentCaptor.forClass(byte[].class);
        verify(runner).execute(cmd.capture(), stdin.capture(), eq(60L));
        assertThat(cmd.getValue()).contains("'docker' 'exec' '-i' '-u' 'root' 'abc123' '/bin/sh' '-c'");
        assertThat(stdin.getValue()).isEqualTo(CONTENT);
        assertThat(cmd.getValue()).doesNotContain("hunter2");
    }

    @Test
    void install_vm_usesSudoAndRemountsReadOnly() throws Exception {
        wire();
        FileSpec spec = new FileSpec(GroveFileInstaller.VM_SECRETS_PATH, "root", "cultivator", "0440", CONTENT);

        installer.install(provider, null, "abc123", Target.VM, spec);

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> stdin = ArgumentCaptor.forClass(byte[].class);
        verify(runner).execute(cmd.capture(), stdin.capture(), eq(60L));
        assertThat(cmd.getValue()).startsWith("sudo sh -c ").doesNotContain("hunter2");
        assertThat(stdin.getValue()).isEqualTo(CONTENT);
        String script = GroveFileInstaller.vmScript(spec);
        assertThat(script).contains("mount -t tmpfs", "remount,rw", "remount,ro", "mode=0755,size=1m",
            "chmod '0440'", "chown 'root':\"$(id -gn 'cultivator')\"");
        assertThat(script.indexOf("mount -t tmpfs")).isLessThan(script.indexOf("remount,rw"));
        assertThat(script.indexOf("remount,rw")).isLessThan(script.indexOf("remount,ro"));
    }

    @Test
    void vmScript_nonSecretsPathOmitsMountLines() {
        String script = GroveFileInstaller.vmScript(
            new FileSpec(GroveFileInstaller.HOOK_PATH, "root", "root", "0644", CONTENT));
        assertThat(script).doesNotContain("mount").contains("mv -f");
    }

    @Test
    void fruitScript_rootRemoteUser() {
        String script = GroveFileInstaller.fruitScript(
            new FileSpec("/dev/shm/orchard/secrets.env", "root", "root", "0400", CONTENT));
        assertThat(script).contains("id -gn 'root'", "chmod '0400'", "umask 077", "mv -f");
    }

    @Test
    void install_vmOnContainerPlot_rejected() {
        FileSpec spec = new FileSpec("/p", "root", "root", "0400", CONTENT);
        assertThatThrownBy(() -> installer.install(provider, mock(ContainerPlot.class), "x", Target.VM, spec))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void installHook_fruit_writesHookThenGuardsBashrc() throws Exception {
        wire();

        installer.installHook(provider, null, "abc123", Target.FRUIT);

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> stdin = ArgumentCaptor.forClass(byte[].class);
        verify(runner).execute(cmd.capture(), stdin.capture(), anyLong());
        assertThat(stdin.getValue()).isEqualTo(GroveFileInstaller.hookScript().getBytes(StandardCharsets.UTF_8));
        assertThat(cmd.getValue()).contains("chmod '\\''0644'\\''");

        ArgumentCaptor<String> second = ArgumentCaptor.forClass(String.class);
        verify(runner).execute(second.capture(), anyLong());
        assertThat(second.getValue()).contains(">> /etc/bash.bashrc", "grep -q orchard-secrets");
    }

    @Test
    void hookScript_restoresAllexport(@TempDir Path dir) throws Exception {
        Path secrets = dir.resolve("secrets.env");
        Files.writeString(secrets, "A='1'\n");
        Path hook = dir.resolve("hook.sh");
        Files.writeString(hook, GroveFileInstaller.hookScript()
            .replace(GroveFileInstaller.FRUIT_SECRETS_PATH, secrets.toString())
            .replace(GroveFileInstaller.VM_SECRETS_PATH, dir.resolve("none.env").toString()));
        LocalCommandRunner local = new LocalCommandRunner(Map.of());

        String off = local.execute("sh -c 'set +a; . " + hook + "; set -o | grep allexport; echo \"$A\"'");
        String on = local.execute("sh -c 'set -a; . " + hook + "; set -o | grep allexport; echo \"$A\"'");

        assertThat(off).containsPattern("allexport\\s+off").contains("1");
        assertThat(on).containsPattern("allexport\\s+on").contains("1");
    }
}
