package dev.orchard.vine;

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
}
