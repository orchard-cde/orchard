package dev.orchard.vine;

import java.util.List;
import java.util.stream.Collectors;

/** POSIX single-quoting for values interpolated into a shell command string. */
public final class ShellQuote {

    private ShellQuote() {}

    /** Wraps {@code s} in single quotes, escaping embedded single quotes; {@code null} quotes as empty. */
    public static String quote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Quotes each token and joins them with spaces, so a shell splits the result back into {@code argv}. */
    public static String join(List<String> argv) {
        return argv.stream().map(ShellQuote::quote).collect(Collectors.joining(" "));
    }
}
