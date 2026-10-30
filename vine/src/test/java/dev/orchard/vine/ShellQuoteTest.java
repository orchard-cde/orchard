package dev.orchard.vine;

import org.junit.jupiter.api.Test;

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
}
