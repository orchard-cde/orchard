package dev.orchard.api.service;

import dev.orchard.vine.ShellQuote;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Renders resolved secrets as a POSIX-shell-sourceable {@code NAME='value'} file. */
public final class SecretEnvFile {

    private SecretEnvFile() {}

    public static byte[] render(Map<String, byte[]> values) {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(values).forEach((name, value) ->
            out.append(name).append('=').append(ShellQuote.quote(new String(value, StandardCharsets.UTF_8))).append('\n'));
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
