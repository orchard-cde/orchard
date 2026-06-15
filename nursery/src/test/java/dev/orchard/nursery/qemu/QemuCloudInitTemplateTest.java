package dev.orchard.nursery.qemu;

import dev.orchard.nursery.CloudInitTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke coverage for the QEMU cloud-init classpath template. Mirrors {@code Ec2UserDataTest}'s
 * style — verifies the rendered YAML contains the runtime-injected ssh key + CLI version
 * and the nodejs / devcontainer install lines that bootstrap each Seedling.
 */
class QemuCloudInitTemplateTest {

    private static final String TEMPLATE_PATH = "/cloud-init/qemu.yaml.tpl";
    private static final String PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5testkey orchard@host";
    private static final String CLI_VERSION = "0.87.0";

    @Test
    void render_installsNodejsAndPinnedDevcontainerCli() {
        String yaml = CloudInitTemplate.render(TEMPLATE_PATH, Map.of(
            "ssh_authorized_keys_block", "    ssh_authorized_keys:\n      - " + PUBLIC_KEY + "\n",
            "cli_version", CLI_VERSION
        ));

        assertThat(yaml)
            .contains("apt-get install -y nodejs")
            .contains("npm install -g @devcontainers/cli@" + CLI_VERSION);
    }

    @Test
    void render_includesInjectedSshPublicKey() {
        String sshBlock = "    ssh_authorized_keys:\n      - " + PUBLIC_KEY + "\n";
        String yaml = CloudInitTemplate.render(TEMPLATE_PATH, Map.of(
            "ssh_authorized_keys_block", sshBlock,
            "cli_version", CLI_VERSION
        ));

        assertThat(yaml)
            .startsWith("#cloud-config\n")
            .contains("ssh_authorized_keys:")
            .contains("- " + PUBLIC_KEY);
    }

    @Test
    void render_omitsSshBlockWhenSubstitutionIsEmpty() {
        String yaml = CloudInitTemplate.render(TEMPLATE_PATH, Map.of(
            "ssh_authorized_keys_block", "",
            "cli_version", CLI_VERSION
        ));

        assertThat(yaml).doesNotContain("ssh_authorized_keys");
        // shell line must flow directly into packages: with no blank line between
        assertThat(yaml).contains("shell: /bin/bash\npackages:");
    }
}
