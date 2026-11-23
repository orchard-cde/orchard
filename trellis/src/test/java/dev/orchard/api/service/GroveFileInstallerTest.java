package dev.orchard.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import dev.orchard.api.service.GroveFileInstaller.FileSpec;
import dev.orchard.api.service.GroveFileInstaller.Target;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.ContainerExec;
import dev.orchard.vine.ExecTarget;
import dev.orchard.vine.LocalCommandRunner;
import dev.orchard.vine.ShellQuote;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class GroveFileInstallerTest {

    private static final byte[] CONTENT = "TOKEN='hunter2'\n".getBytes(StandardCharsets.UTF_8);

    private final GroveFileInstaller installer = new GroveFileInstaller();
    private final CommandRunner runner = mock(CommandRunner.class);
    private final ExecTarget exec = new ExecTarget(runner, "/ws", UUID.randomUUID(), ContainerRuntime.DOCKER);
    private static final FileSpec HOOK_SPEC = new FileSpec(GroveFileInstaller.HOOK_PATH, "root", "root", "0644",
        GroveFileInstaller.hookScript().getBytes(StandardCharsets.UTF_8));

    @Test
    void install_fruit_execsAsRootWithStdin() throws Exception {
        FileSpec spec = new FileSpec(GroveFileInstaller.FRUIT_SECRETS_PATH, "vscode", "vscode", "0400", CONTENT);

        installer.install(exec, "abc123", Target.FRUIT, spec);

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> stdin = ArgumentCaptor.forClass(byte[].class);
        verify(runner).execute(cmd.capture(), stdin.capture(), eq(60L));
        assertThat(cmd.getValue()).isEqualTo(ShellQuote.join(ContainerExec.of("docker", "abc123")
            .interactive(true, false).user("root").shell(GroveFileInstaller.fruitScript(spec)).argv()));
        assertThat(cmd.getValue()).startsWith("'docker' 'exec' '-i' '-u' 'root' 'abc123' '/bin/sh' '-c'");
        assertThat(stdin.getValue()).isEqualTo(CONTENT);
        assertThat(cmd.getValue()).doesNotContain("hunter2");
    }

    @Test
    void install_vm_usesSudoAndRemountsReadOnly() throws Exception {
        FileSpec spec = new FileSpec(GroveFileInstaller.VM_SECRETS_PATH, "root", "cultivator", "0440", CONTENT);

        installer.install(exec, "abc123", Target.VM, spec);

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> stdin = ArgumentCaptor.forClass(byte[].class);
        verify(runner).execute(cmd.capture(), stdin.capture(), eq(60L));
        String script = GroveFileInstaller.vmScript(spec);
        assertThat(cmd.getValue()).isEqualTo("sudo sh -c " + ShellQuote.quote(script));
        assertThat(cmd.getValue()).doesNotContain("hunter2");
        assertThat(stdin.getValue()).isEqualTo(CONTENT);
        assertThat(script).contains("mount -t tmpfs", "remount,rw", "remount,ro", "mode=0755,size=64m",
            "chmod '0440'", "g=$(id -gn 'cultivator')", "chown 'root':\"$g\"");
        assertThat(script.indexOf("g=$(id -gn")).isLessThan(script.indexOf("chown"));
        assertThat(script).contains("trap 'rm -f \"$t\"; mount -o remount,ro \"$d\"");
        assertThat(script.lastIndexOf("remount,ro")).isGreaterThan(script.indexOf("mv -f"));
        assertThat(script.indexOf("mount -t tmpfs")).isLessThan(script.indexOf("remount,rw"));
        assertThat(script.indexOf("remount,rw")).isLessThan(script.lastIndexOf("remount,ro"));
    }

    @Test
    void scripts_hardenParentAssignGroupFirstAndCleanTemp() {
        FileSpec spec = new FileSpec(GroveFileInstaller.FRUIT_SECRETS_PATH, "vscode", "vscode", "0400", CONTENT);
        String fruit = GroveFileInstaller.fruitScript(spec);
        assertThat(fruit).contains("[ -L \"$d\" ]", "o=$(stat -c %u \"$d\")", "rm -rf \"$d\"",
            "chown root:root \"$d\"", "chmod 755 \"$d\"", "trap 'rm -f \"$t\"' EXIT", "fi; [ ! -L \"$d\" ];");
        assertThat(fruit).doesNotContain("mkdir -p").doesNotContain("2>/dev/null");
        assertThat(fruit.indexOf("mkdir \"$d\"")).isGreaterThan(fruit.indexOf("rm -rf \"$d\""));
        assertThat(fruit.indexOf("chown root:root")).isGreaterThan(fruit.indexOf("fi; [ ! -L \"$d\" ];"));

        String hookFruit = GroveFileInstaller.fruitScript(
            new FileSpec(GroveFileInstaller.HOOK_PATH, "root", "root", "0644", CONTENT));
        assertThat(hookFruit).contains("mkdir -p \"$d\"", "chmod 755 \"$d\"")
            .doesNotContain("rm -rf", "stat", "chown root:root");
        assertThat(fruit.indexOf("rm -f \"$t\"; cat")).isGreaterThan(fruit.indexOf("chmod 755"));
        assertThat(fruit.indexOf("g=$(id -gn 'vscode')")).isBetween(fruit.indexOf("cat >"), fruit.indexOf("chown 'vscode'"));
        assertThat(fruit).doesNotContain("\"$(id -gn");

        String vm = GroveFileInstaller.vmScript(
            new FileSpec(GroveFileInstaller.VM_SECRETS_PATH, "root", "cultivator", "0440", CONTENT));
        assertThat(vm).contains("rm -f \"$t\"; cat").doesNotContain("\"$(id -gn");
        assertThat(vm.indexOf("g=$(id -gn 'cultivator')")).isBetween(vm.indexOf("cat >"), vm.indexOf("chown 'root'"));

        String hookVm = GroveFileInstaller.vmScript(
            new FileSpec(GroveFileInstaller.HOOK_PATH, "root", "root", "0644", CONTENT));
        assertThat(hookVm).contains("trap 'rm -f \"$t\"' EXIT");
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
    void installHook_fruit_writesHookAndGuardsInOneStdinExec() throws Exception {
        installer.installHook(exec, "abc123", Target.FRUIT);

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> stdin = ArgumentCaptor.forClass(byte[].class);
        verify(runner).execute(cmd.capture(), stdin.capture(), eq(60L));
        verifyNoMoreInteractions(runner);
        String script = GroveFileInstaller.hookInstallScript(Target.FRUIT, HOOK_SPEC);
        assertThat(stdin.getValue()).isEqualTo(GroveFileInstaller.hookScript().getBytes(StandardCharsets.UTF_8));
        assertThat(cmd.getValue()).isEqualTo(ContainerExec.of("docker", "abc123")
            .interactive(true, false).user("root").shell(script).commandLine());
        assertHookWriteThenGuards(script);
    }

    @Test
    void installHook_vm_writesHookAndGuardsInOneSudoExec() throws Exception {
        installer.installHook(exec, "abc123", Target.VM);

        ArgumentCaptor<String> cmd = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> stdin = ArgumentCaptor.forClass(byte[].class);
        verify(runner).execute(cmd.capture(), stdin.capture(), eq(60L));
        verifyNoMoreInteractions(runner);
        String script = GroveFileInstaller.hookInstallScript(Target.VM, HOOK_SPEC);
        assertThat(stdin.getValue()).isEqualTo(GroveFileInstaller.hookScript().getBytes(StandardCharsets.UTF_8));
        assertThat(cmd.getValue()).isEqualTo("sudo sh -c " + ShellQuote.quote(script));
        assertThat(script).doesNotContain("mount");
        assertHookWriteThenGuards(script);
    }

    private static void assertHookWriteThenGuards(String script) {
        assertThat(script).contains("p='" + GroveFileInstaller.HOOK_PATH + "'", "cat > \"$t\"",
            "chown 'root':\"$g\"", "chmod '0644'", "mv -f \"$t\" \"$p\"",
            GroveFileInstaller.BASHRC_GUARD, GroveFileInstaller.ZPROFILE_GUARD);
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
