package dev.orchard.nursery;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

class NurseryRuntimeHintsTest {

    @Test
    void registersCloudInitTemplatesAsResources() {
        RuntimeHints hints = new RuntimeHints();
        new NurseryRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.resource().forResource("cloud-init/qemu.yaml.tpl")).accepts(hints);
        assertThat(RuntimeHintsPredicates.resource().forResource("cloud-init/aws.yaml.tpl")).accepts(hints);
    }
}
