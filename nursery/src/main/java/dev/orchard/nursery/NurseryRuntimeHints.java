package dev.orchard.nursery;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers the cloud-init YAML templates ({@code cloud-init/*.tpl}) as native-image
 * resources. {@link CloudInitTemplate} loads them via {@code getResourceAsStream}, which
 * returns null in a native image unless the resources are explicitly registered — without
 * this, seedling planting fails with "Cloud-init template not found" and groves go BLIGHTED.
 */
public class NurseryRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("cloud-init/*.tpl");
    }
}
