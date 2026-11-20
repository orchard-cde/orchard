package dev.orchard.api.service;

import dev.orchard.core.model.Secret;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Renders resolved secrets as a POSIX-shell-sourceable {@code NAME='value'} file. */
public final class SecretEnvFile {

    public record Rendered(byte[] bytes, List<String> skippedNames) {}

    private SecretEnvFile() {}

    public static Rendered render(Map<String, byte[]> values) {
        StringBuilder out = new StringBuilder();
        List<String> skipped = new ArrayList<>();
        new TreeMap<>(values).forEach((name, value) -> {
            if (!Secret.NAME.matcher(name).matches()) {
                skipped.add(name);
                return;
            }
            String text = new String(value, StandardCharsets.UTF_8);
            out.append(name).append("='").append(text.replace("'", "'\\''")).append("'\n");
        });
        return new Rendered(out.toString().getBytes(StandardCharsets.UTF_8), List.copyOf(skipped));
    }
}
