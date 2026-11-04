package dev.orchard.vine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerExecTest {

    @Test
    void shell_bare() {
        assertThat(ContainerExec.of("docker", "abc").shell("ls -la").argv())
            .containsExactly("docker", "exec", "abc", "/bin/sh", "-c", "ls -la");
    }

    @Test
    void shell_withUser() {
        assertThat(ContainerExec.of("docker", "abc").user("dev").shell("id").argv())
            .containsExactly("docker", "exec", "-u", "dev", "abc", "/bin/sh", "-c", "id");
    }

    @Test
    void nullUserAndWorkdir_addNoFlags() {
        assertThat(ContainerExec.of("docker", "abc").user(null).workdir(null).shell("id").argv())
            .containsExactly("docker", "exec", "abc", "/bin/sh", "-c", "id");
    }

    @Test
    void loginShell_flags() {
        assertThat(ContainerExec.of("podman", "abc").loginShell(false).argv())
            .containsExactly("podman", "exec", "abc", "/bin/sh", "-l");
        assertThat(ContainerExec.of("docker", "abc").loginShell(true).argv())
            .containsExactly("docker", "exec", "abc", "/bin/sh", "-il");
    }

    @Test
    void everyFlag_inFixedOrder_regardlessOfCallOrder() {
        List<String> argv = ContainerExec.of("podman", "abc")
            .workdir("/workspaces/p")
            .user("dev")
            .env("TERM", "xterm")
            .interactive(true, true)
            .loginShell(false)
            .argv();

        assertThat(argv).containsExactly("podman", "exec", "-i", "-t", "-e", "TERM=xterm", "-u", "dev",
            "-w", "/workspaces/p", "abc", "/bin/sh", "-l");
    }

    @Test
    void interactive_stdinOnly() {
        assertThat(ContainerExec.of("docker", "abc").interactive(true, false).shell("cat").argv())
            .containsExactly("docker", "exec", "-i", "abc", "/bin/sh", "-c", "cat");
    }

    @Test
    void builder_isImmutable() {
        ContainerExec base = ContainerExec.of("docker", "abc");
        base.user("dev").env("TERM", "xterm").interactive(true, true).workdir("/w");

        assertThat(base.shell("id").argv()).containsExactly("docker", "exec", "abc", "/bin/sh", "-c", "id");
    }

    @Test
    void command_isOneToken() {
        assertThat(ContainerExec.of("docker", "abc").shell("echo 'x'; id").argv())
            .endsWith("-c", "echo 'x'; id");
    }
}
