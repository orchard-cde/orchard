package dev.orchard.nursery.aws;

import dev.orchard.core.model.Seedling.SeedlingSpec;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Renders the cloud-init {@code #cloud-config} YAML used to bootstrap an EC2 instance.
 * Pure function — no I/O, no SDK calls. The same template is used by QEMU's
 * NoCloud ISO; see {@code QemuSeedlingProvider} for the original.
 */
public final class Ec2UserData {

    private Ec2UserData() {}

    /**
     * Renders cloud-init YAML for the given seedling spec, SSH public key, and pinned
     * {@code @devcontainers/cli} version.
     *
     * @param spec               the seedling spec (currently unused by the template, accepted for future use)
     * @param publicKey          the orchard SSH public key, e.g. {@code ssh-ed25519 AAAA... orchard@host}
     * @param devcontainerCliVersion the npm version of {@code @devcontainers/cli} to install
     * @return raw YAML beginning with {@code #cloud-config\n}
     * @throws IllegalArgumentException if {@code publicKey} or {@code devcontainerCliVersion} is null or blank
     */
    public static String render(SeedlingSpec spec, String publicKey, String devcontainerCliVersion) {
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalArgumentException("publicKey must not be null or blank");
        }
        if (devcontainerCliVersion == null || devcontainerCliVersion.isBlank()) {
            throw new IllegalArgumentException("devcontainerCliVersion must not be null or blank");
        }
        return """
                #cloud-config
                users:
                  - name: cultivator
                    sudo: ALL=(ALL) NOPASSWD:ALL
                    shell: /bin/bash
                    ssh_authorized_keys:
                      - %s
                packages:
                  - docker.io
                  - git
                  - curl
                runcmd:
                  - curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
                  - apt-get install -y nodejs
                  - npm install -g @devcontainers/cli@%s
                  - systemctl enable --now docker
                  - usermod -aG docker cultivator
                  - mkdir -p /workspace
                  - chown cultivator:cultivator /workspace
                """.formatted(publicKey, devcontainerCliVersion);
    }

    /**
     * Renders cloud-init YAML and Base64-encodes it for use as
     * {@link software.amazon.awssdk.services.ec2.model.RunInstancesRequest#userData()}.
     */
    public static String renderBase64(SeedlingSpec spec, String publicKey, String devcontainerCliVersion) {
        String yaml = render(spec, publicKey, devcontainerCliVersion);
        return Base64.getEncoder().encodeToString(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
