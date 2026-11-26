package dev.orchard.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import dev.orchard.api.service.GroveFileInstaller.Target;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.trellis.config.KnotholeConfig.KnotholeProperties;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.ContainerExec;
import dev.orchard.vine.ExecTarget;
import dev.orchard.vine.LocalCommandRunner;
import dev.orchard.vine.ShellQuote;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

class GroveFileInstallerTest {

    private static final byte[] CONTENT = "TOKEN='hunter2'\n".getBytes(StandardCharsets.UTF_8);

    private final GroveFileInstaller installer = new GroveFileInstaller(new KnotholeProperties(
        null, null, DataSize.ofKilobytes(64), Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(60)));
    private final CommandRunner runner = mock(CommandRunner.class);
    private final ExecTarget exec = new ExecTarget(runner, "/ws", UUID.randomUUID(), ContainerRuntime.DOCKER);
    private final ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
    private final ArgumentCaptor<byte[]> stdin = ArgumentCaptor.forClass(byte[].class);

    @Test
    void installSecrets_fruit_execsAsRootWithStdin() throws Exception {
        installer.installSecrets(exec, "abc123", Target.FRUIT, "vscode", CONTENT);

        verify(runner).execute(cmd.capture(), stdin.capture(), eq(60L));
        verifyNoMoreInteractions(runner);
        String script = GroveFileInstaller.secretsScript(Target.FRUIT, "vscode");
        assertThat(cmd.getValue()).isEqualTo(ContainerExec.of("docker", "abc123")
            .interactive(true, false).user("root").shell(script).commandLine());
        assertThat(cmd.getValue()).startsWith("'docker' 'exec' '-i' '-u' 'root' 'abc123' '/bin/sh' '-c'");
        assertThat(cmd.getValue()).doesNotContain("hunter2");
        assertThat(stdin.getValue()).isEqualTo(CONTENT);
    }

    @Test
    void fruitSecretsScript_hardensParentThenWritesWithGroupBeforeChown() {
        String script = GroveFileInstaller.secretsScript(Target.FRUIT, "vscode");

        assertThat(script).doesNotContain("\n", "mkdir -p", "2>/dev/null", "\"$(id -gn");
        assertThat(script).contains("set -e; d=/dev/shm/orchard; p=$d/secrets.env; t=$p.tmp",
            "trap 'rm -f \"$t\"' EXIT", "o=$(stat -c %u \"$d\")", "rm -rf \"$d\"", "fi; [ ! -L \"$d\" ];",
            "chown root:root \"$d\"; chmod 755 \"$d\"", "umask 077", "rm -f \"$t\"; cat > \"$t\"",
            "g=$(id -gn 'vscode')", "chown root:\"$g\" \"$t\"; chmod 0440 \"$t\"", "mv -f \"$t\" \"$p\"");
        assertThat(script.indexOf("mkdir \"$d\"")).isGreaterThan(script.indexOf("rm -rf \"$d\""));
        assertThat(script.indexOf("chown root:root")).isGreaterThan(script.indexOf("fi; [ ! -L \"$d\" ];"));
        assertThat(script.indexOf("cat >")).isGreaterThan(script.indexOf("chmod 755"));
        assertThat(script.indexOf("g=$(id -gn 'vscode')"))
            .isBetween(script.indexOf("cat >"), script.indexOf("chown root:\"$g\""));
    }

    @Test
    void secretsScript_quotesTheReader() {
        assertThat(GroveFileInstaller.secretsScript(Target.FRUIT, "a b")).contains("id -gn 'a b'");
        assertThat(GroveFileInstaller.secretsScript(Target.VM, "cultivator")).contains("id -gn 'cultivator'");
    }

    @Test
    void installSecrets_vm_usesSudoAndRemountsReadOnly() throws Exception {
        installer.installSecrets(exec, "abc123", Target.VM, "cultivator", CONTENT);

        verify(runner).execute(cmd.capture(), stdin.capture(), eq(60L));
        verifyNoMoreInteractions(runner);
        String script = GroveFileInstaller.secretsScript(Target.VM, "cultivator");
        assertThat(cmd.getValue()).isEqualTo("sudo sh -c " + ShellQuote.quote(script));
        assertThat(cmd.getValue()).doesNotContain("hunter2");
        assertThat(stdin.getValue()).isEqualTo(CONTENT);
        assertThat(script).doesNotContain("\n");
        assertThat(script).contains("d=/run/orchard/secrets; p=$d/secrets.env; t=$p.tmp",
            "mount -t tmpfs -o mode=0755,size=64m tmpfs \"$d\"", "mount -o remount,rw \"$d\"",
            "trap 'rm -f \"$t\"; mount -o remount,ro \"$d\" 2>/dev/null || true' EXIT",
            "rm -f \"$t\"; cat > \"$t\"", "g=$(id -gn 'cultivator')", "chown root:\"$g\" \"$t\"; chmod 0440 \"$t\"",
            "mv -f \"$t\" \"$p\"; mount -o remount,ro \"$d\"");
        assertThat(script.indexOf("mount -t tmpfs")).isLessThan(script.indexOf("remount,rw"));
        assertThat(script.indexOf("remount,rw")).isLessThan(script.indexOf("cat >"));
        assertThat(script.indexOf("g=$(id -gn 'cultivator')"))
            .isBetween(script.indexOf("cat >"), script.indexOf("chown root:\"$g\""));
        assertThat(script.lastIndexOf("remount,ro")).isGreaterThan(script.indexOf("mv -f"));
    }

    @Test
    void installHook_fruit_writesHookAndGuardsInOneStdinExec() throws Exception {
        installer.installHook(exec, "abc123", Target.FRUIT);

        verify(runner).execute(cmd.capture(), stdin.capture(), eq(60L));
        verifyNoMoreInteractions(runner);
        String script = GroveFileInstaller.hookInstallScript();
        assertThat(stdin.getValue()).isEqualTo(GroveFileInstaller.hookScript().getBytes(StandardCharsets.UTF_8));
        assertThat(cmd.getValue()).isEqualTo(ContainerExec.of("docker", "abc123")
            .interactive(true, false).user("root").shell(script).commandLine());
        assertHookWriteThenGuards(script);
    }

    @Test
    void installHook_vm_writesHookAndGuardsInOneSudoExec() throws Exception {
        installer.installHook(exec, "abc123", Target.VM);

        verify(runner).execute(cmd.capture(), stdin.capture(), eq(60L));
        verifyNoMoreInteractions(runner);
        String script = GroveFileInstaller.hookInstallScript();
        assertThat(stdin.getValue()).isEqualTo(GroveFileInstaller.hookScript().getBytes(StandardCharsets.UTF_8));
        assertThat(cmd.getValue()).isEqualTo("sudo sh -c " + ShellQuote.quote(script));
        assertHookWriteThenGuards(script);
    }

    private static void assertHookWriteThenGuards(String script) {
        assertThat(script).doesNotContain("\n", "mount");
        assertThat(script).contains("p=" + GroveFileInstaller.HOOK_PATH + "; t=$p.tmp", "trap 'rm -f \"$t\"' EXIT",
            "mkdir -p /etc/profile.d; chmod 755 /etc/profile.d", "umask 022", "rm -f \"$t\"; cat > \"$t\"",
            "chown root:root \"$t\"; chmod 0644 \"$t\"", "mv -f \"$t\" \"$p\"",
            GroveFileInstaller.BASHRC_GUARD, GroveFileInstaller.ZPROFILE_GUARD);
        assertThat(script.indexOf("umask 022")).isLessThan(script.indexOf("cat >"));
        assertThat(script.indexOf("mv -f")).isLessThan(script.indexOf(GroveFileInstaller.BASHRC_GUARD));
        assertThat(script.indexOf(GroveFileInstaller.BASHRC_GUARD))
            .isLessThan(script.indexOf(GroveFileInstaller.ZPROFILE_GUARD));
        assertThat(GroveFileInstaller.BASHRC_GUARD).contains(">> /etc/bash.bashrc", "grep -q orchard-secrets");
        assertThat(GroveFileInstaller.ZPROFILE_GUARD).contains("/etc/zsh/zprofile", "/etc/zprofile",
            "grep -q orchard-secrets",
            "[ -r /etc/profile.d/orchard-secrets.sh ] && . /etc/profile.d/orchard-secrets.sh");
    }

    @Test
    void hookScript_readsBothSecretsPaths() {
        assertThat(GroveFileInstaller.hookScript())
            .contains(GroveFileInstaller.FRUIT_SECRETS_PATH, GroveFileInstaller.VM_SECRETS_PATH);
    }

    @Test
    void zprofileGuard_appendsOnceToDebianOrPlainPath(@TempDir Path dir) throws Exception {
        LocalCommandRunner local = new LocalCommandRunner(Map.of());
        Path debian = dir.resolve("debian");
        Files.createDirectories(debian.resolve("zsh"));
        Path plain = dir.resolve("plain");
        Files.createDirectories(plain);
        for (Path etc : new Path[] {debian, plain}) {
            String script = GroveFileInstaller.ZPROFILE_GUARD.replace("/etc/z", etc + "/z");
            local.execute("sh -c " + ShellQuote.quote(script));
            local.execute("sh -c " + ShellQuote.quote(script));
        }
        String line = "[ -r /etc/profile.d/orchard-secrets.sh ] && . /etc/profile.d/orchard-secrets.sh";
        assertThat(Files.readString(debian.resolve("zsh/zprofile"))).isEqualTo("\n" + line + "\n");
        assertThat(Files.readString(plain.resolve("zprofile"))).isEqualTo("\n" + line + "\n");
        assertThat(plain.resolve("zsh")).doesNotExist();
    }

    @Test
    void hookScript_restoresAllexport(@TempDir Path dir) throws Exception {
        Path first = dir.resolve("first.env");
        Files.writeString(first, "A='1'\n");
        Path second = dir.resolve("second.env");
        Files.writeString(second, "A='2'\n");
        Path hook = dir.resolve("hook.sh");
        Files.writeString(hook, GroveFileInstaller.hookScript()
            .replace(GroveFileInstaller.FRUIT_SECRETS_PATH, first.toString())
            .replace(GroveFileInstaller.VM_SECRETS_PATH, second.toString()));
        LocalCommandRunner local = new LocalCommandRunner(Map.of());
        String probe = "; set -o | grep allexport; env | grep '^A='";

        String off = local.execute("sh -c \"set +a; . " + hook + probe + "\"");
        String on = local.execute("sh -c \"set -a; . " + hook + probe + "\"");

        assertThat(off).containsPattern("allexport\\s+off").containsPattern("(?m)^A=1$").doesNotContain("A=2");
        assertThat(on).containsPattern("allexport\\s+on").containsPattern("(?m)^A=1$").doesNotContain("A=2");
    }
}
