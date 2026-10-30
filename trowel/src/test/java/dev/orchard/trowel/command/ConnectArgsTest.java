package dev.orchard.trowel.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectArgsTest {

    private static final String CONNECTION = "ssh -p 2222 0b9f@localhost";
    private static final String NO_KEY = "no SSH key provisioned for this target; run: trowel ssh-key add";

    @TempDir
    Path keysDir;

    @Test
    void connectArgv_exactlyOneIdentity() throws Exception {
        Files.writeString(keysDir.resolve("dev-server"), "k");
        Files.writeString(keysDir.resolve("other"), "k");

        List<String> argv = GroveCommand.connectArgv(CONNECTION, keysDir, "dev-server");

        assertThat(argv).containsExactly(
            "ssh",
            "-i", keysDir.resolve("dev-server").toString(),
            "-o", "IdentitiesOnly=yes",
            "-p", "2222",
            "0b9f@localhost");
        assertThat(argv.stream().filter("-i"::equals)).hasSize(1);
        assertThat(String.join(" ", argv)).doesNotContain("other");
    }

    @Test
    void connectArgv_trailingCommand_keepsFlagsBeforeDestination() throws Exception {
        Files.writeString(keysDir.resolve("dev-server"), "k");

        List<String> argv = GroveCommand.connectArgv(CONNECTION + " uptime", keysDir, "dev-server");

        assertThat(argv.subList(0, 5)).containsExactly(
            "ssh", "-i", keysDir.resolve("dev-server").toString(), "-o", "IdentitiesOnly=yes");
        assertThat(argv.indexOf("0b9f@localhost")).isGreaterThan(argv.indexOf("IdentitiesOnly=yes"));
        assertThat(argv.get(argv.size() - 1)).isEqualTo("uptime");
    }

    @Test
    void connectArgv_noKey_throwsNamingSshKeyAdd() {
        assertThatThrownBy(() -> GroveCommand.connectArgv(CONNECTION, keysDir, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(NO_KEY);
    }

    @Test
    void connectArgv_missingPrivateFile_throws() {
        assertThatThrownBy(() -> GroveCommand.connectArgv(CONNECTION, keysDir, "dev-server"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(NO_KEY);
    }
}
