package dev.orchard.nursery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Loads a cloud-init YAML template from the classpath and substitutes ${var}
 * placeholders. Kept intentionally small — no Velocity / Mustache dependency.
 */
public final class CloudInitTemplate {

    private CloudInitTemplate() {}

    /**
     * @param resourcePath classpath resource (e.g. "/cloud-init/qemu.yaml.tpl")
     * @param vars map of placeholder name -> value (no surrounding ${...} in keys)
     * @return rendered template
     */
    public static String render(String resourcePath, Map<String, String> vars) {
        String tpl;
        try (var in = CloudInitTemplate.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalArgumentException("Cloud-init template not found: " + resourcePath);
            tpl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (var entry : vars.entrySet()) {
            Objects.requireNonNull(entry.getValue(), () -> "Null value for placeholder " + entry.getKey());
            tpl = tpl.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return tpl;
    }
}
