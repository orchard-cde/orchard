package dev.orchard.vine;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShellQuoteTest {

    @Test
    void plainWord_isStillQuoted() {
        assertThat(ShellQuote.quote("abc")).isEqualTo("'abc'");
    }

    @Test
    void stringWithSpace_isOneToken() {
        assertThat(ShellQuote.quote("/tmp/a b/ws")).isEqualTo("'/tmp/a b/ws'");
    }

    @Test
    void embeddedSingleQuote_isEscaped() {
        assertThat(ShellQuote.quote("it's")).isEqualTo("'it'\\''s'");
    }

    @Test
    void substitutionAndSeparator_stayLiteral() {
        assertThat(ShellQuote.quote("$(id)")).isEqualTo("'$(id)'");
        assertThat(ShellQuote.quote("a;b")).isEqualTo("'a;b'");
    }

    @Test
    void nullAndEmpty_quoteAsEmpty() {
        assertThat(ShellQuote.quote(null)).isEqualTo("''");
        assertThat(ShellQuote.quote("")).isEqualTo("''");
    }

    @Test
    void join_quotesEveryToken() {
        assertThat(ShellQuote.join(List.of("podman", "exec", "abc", "/bin/sh", "-c", "ls")))
            .isEqualTo("'podman' 'exec' 'abc' '/bin/sh' '-c' 'ls'");
    }

    @Test
    void join_keepsSpacesAndQuotesInsideOneToken() {
        assertThat(ShellQuote.join(List.of("sh", "-c", "echo 'a b'; id")))
            .isEqualTo("'sh' '-c' 'echo '\\''a b'\\''; id'");
    }

    @Test
    void join_empty() {
        assertThat(ShellQuote.join(List.of())).isEmpty();
    }

    @Test
    void join_shellSplitsBackToTheSameArgv() throws Exception {
        List<String> argv = List.of("a b", "it's", "$(id)", "a;b", "", "-c", "echo 'x'; touch /tmp/y");
        Process p = new ProcessBuilder("/bin/sh", "-c", "printf '%s\\n' " + ShellQuote.join(argv))
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(p.waitFor()).isZero();
        assertThat(out.lines().toList()).containsExactlyElementsOf(argv);
    }
}
