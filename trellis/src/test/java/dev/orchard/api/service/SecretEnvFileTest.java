package dev.orchard.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orchard.vine.LocalCommandRunner;
import dev.orchard.vine.ShellQuote;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretEnvFileTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void render_quotesSingleQuotes() {
        byte[] rendered = SecretEnvFile.render(Map.of("NAME", utf8("a'b")));

        assertThat(new String(rendered, StandardCharsets.UTF_8)).isEqualTo("NAME='a'\\''b'\n");
    }

    @Test
    void render_roundTripsThroughSh(@TempDir Path dir) throws Exception {
        String value = "line1\nit's é";
        Path file = dir.resolve("secrets.env");
        Files.write(file, SecretEnvFile.render(Map.of("TOKEN", utf8(value))));

        String out = new LocalCommandRunner().execute(
            "sh -c " + ShellQuote.quote(". " + ShellQuote.quote(file.toString()) + "; printf %s \"$TOKEN\""));

        assertThat(out).isEqualTo(value + "\n");
    }

    @Test
    void render_emptyValue_allowed() {
        byte[] rendered = SecretEnvFile.render(Map.of("EMPTY", new byte[0]));

        assertThat(new String(rendered, StandardCharsets.UTF_8)).isEqualTo("EMPTY=''\n");
    }
}
