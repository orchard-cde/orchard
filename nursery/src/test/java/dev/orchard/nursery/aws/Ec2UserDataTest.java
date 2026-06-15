package dev.orchard.nursery.aws;

import dev.orchard.core.model.Seedling.SeedlingSpec;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ec2UserDataTest {

    private static final String PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5testkey orchard@host";
    private static final String CLI_VERSION = "0.87.0";

    @Test
    void render_startsWithCloudConfigShebang() {
        String yaml = Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY, CLI_VERSION);

        assertThat(yaml).startsWith("#cloud-config\n");
    }

    @Test
    void render_includesCultivatorUserWithSudoAndKey() {
        String yaml = Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY, CLI_VERSION);

        assertThat(yaml)
            .contains("- name: cultivator")
            .contains("sudo: ALL=(ALL) NOPASSWD:ALL")
            .contains("shell: /bin/bash")
            .contains("ssh_authorized_keys:")
            .contains("- " + PUBLIC_KEY);
    }

    @Test
    void render_installsDockerAndGit() {
        String yaml = Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY, CLI_VERSION);

        assertThat(yaml)
            .contains("packages:")
            .contains("- docker.io")
            .contains("- git")
            .contains("- curl");
    }

    @Test
    void render_installsNodejsAndDevcontainerCli() {
        String yaml = Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY, CLI_VERSION);

        assertThat(yaml)
            .contains("curl -fsSL https://deb.nodesource.com/setup_20.x")
            .contains("apt-get install -y nodejs")
            .contains("npm install -g @devcontainers/cli@" + CLI_VERSION);
    }

    @Test
    void render_pinsCliVersionFromArgument() {
        String yaml = Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY, "1.2.3");

        assertThat(yaml).contains("npm install -g @devcontainers/cli@1.2.3");
    }

    @Test
    void render_runcmdEnablesDockerAndPreparesWorkspace() {
        String yaml = Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY, CLI_VERSION);

        assertThat(yaml)
            .contains("runcmd:")
            .contains("systemctl enable --now docker")
            .contains("usermod -aG docker cultivator")
            .contains("mkdir -p /workspace")
            .contains("chown cultivator:cultivator /workspace");
    }

    @Test
    void render_outputForAllSpecSizes_isStable() {
        // Spec size is not currently part of the YAML, but the function must accept all three.
        assertThat(Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY, CLI_VERSION)).contains("cultivator");
        assertThat(Ec2UserData.render(SeedlingSpec.medium(), PUBLIC_KEY, CLI_VERSION)).contains("cultivator");
        assertThat(Ec2UserData.render(SeedlingSpec.large(), PUBLIC_KEY, CLI_VERSION)).contains("cultivator");
    }

    @Test
    void render_blankPublicKey_throws() {
        assertThatThrownBy(() -> Ec2UserData.render(SeedlingSpec.small(), "", CLI_VERSION))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("publicKey");

        assertThatThrownBy(() -> Ec2UserData.render(SeedlingSpec.small(), null, CLI_VERSION))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("publicKey");
    }

    @Test
    void render_blankCliVersion_throws() {
        assertThatThrownBy(() -> Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("devcontainerCliVersion");

        assertThatThrownBy(() -> Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("devcontainerCliVersion");
    }

    @Test
    void renderBase64_isDecodableToRawYaml() {
        String base64 = Ec2UserData.renderBase64(SeedlingSpec.small(), PUBLIC_KEY, CLI_VERSION);
        String decoded = new String(Base64.getDecoder().decode(base64));

        assertThat(decoded)
            .startsWith("#cloud-config\n")
            .contains("cultivator")
            .contains("npm install -g @devcontainers/cli@" + CLI_VERSION);
    }
}
