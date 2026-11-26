package dev.orchard.trowel.command;

import dev.orchard.trowel.client.OrchardClient.GroveResponse;
import dev.orchard.trowel.client.OrchardClient.SecretWarning;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GroveCommandPrintSecretsTest {

    private static String print(List<SecretWarning> missing) {
        GroveResponse grove = new GroveResponse(UUID.randomUUID(), "g", "url", "main", null,
            "FLOURISHING", null, null, List.of(), "now", "now", missing);
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        try {
            GroveCommand.printGrove(grove);
        } finally {
            System.setOut(original);
        }
        return out.toString();
    }

    @Test
    void warningWithAllFields_printsExactBlock() {
        String output = print(List.of(new SecretWarning("GITHUB_TOKEN", "a token", "https://docs.example/t")));

        assertThat(output).contains(
            "  Missing secrets (stored values reach the grove on its next start):\n"
                + "    GITHUB_TOKEN  -  a token  https://docs.example/t\n");
    }

    @Test
    void warningWithoutOptionalFields_omitsThem() {
        String output = print(List.of(new SecretWarning("A", null, null), new SecretWarning("B", "d", null)));

        assertThat(output).contains("    A\n", "    B  -  d\n").doesNotContain("null");
    }

    @Test
    void emptyOrNull_printsNothing() {
        assertThat(print(List.of())).doesNotContain("Missing secrets");
        assertThat(print(null)).doesNotContain("Missing secrets");
    }
}
