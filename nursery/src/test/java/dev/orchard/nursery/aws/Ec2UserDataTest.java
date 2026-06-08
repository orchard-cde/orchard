package dev.orchard.nursery.aws;

import dev.orchard.core.model.Seedling.SeedlingSpec;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ec2UserDataTest {

    private static final String PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5testkey orchard@host";

    @Test
    void render_startsWithCloudConfigShebang() {
        String yaml = Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY);

        assertThat(yaml).startsWith("#cloud-config\n");
    }

    @Test
    void render_includesCultivatorUserWithSudoAndKey() {
        String yaml = Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY);

        assertThat(yaml)
            .contains("- name: cultivator")
            .contains("sudo: ALL=(ALL) NOPASSWD:ALL")
            .contains("shell: /bin/bash")
            .contains("ssh_authorized_keys:")
            .contains("- " + PUBLIC_KEY);
    }

    @Test
    void render_installsDockerAndGit() {
        String yaml = Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY);

        assertThat(yaml)
            .contains("packages:")
            .contains("- docker.io")
            .contains("- git");
    }

    @Test
    void render_runcmdEnablesDockerAndPreparesWorkspace() {
        String yaml = Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY);

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
        assertThat(Ec2UserData.render(SeedlingSpec.small(), PUBLIC_KEY)).contains("cultivator");
        assertThat(Ec2UserData.render(SeedlingSpec.medium(), PUBLIC_KEY)).contains("cultivator");
        assertThat(Ec2UserData.render(SeedlingSpec.large(), PUBLIC_KEY)).contains("cultivator");
    }

    @Test
    void render_blankPublicKey_throws() {
        assertThatThrownBy(() -> Ec2UserData.render(SeedlingSpec.small(), ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("publicKey");

        assertThatThrownBy(() -> Ec2UserData.render(SeedlingSpec.small(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("publicKey");
    }

    @Test
    void renderBase64_isDecodableToRawYaml() {
        String base64 = Ec2UserData.renderBase64(SeedlingSpec.small(), PUBLIC_KEY);
        String decoded = new String(Base64.getDecoder().decode(base64));

        assertThat(decoded).startsWith("#cloud-config\n").contains("cultivator");
    }
}
