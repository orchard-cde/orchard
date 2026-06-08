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
     * Renders cloud-init YAML for the given seedling spec and authorized SSH public key.
     *
     * @param spec      the seedling spec (currently unused by the template, accepted for future use)
     * @param publicKey the orchard SSH public key, e.g. {@code ssh-ed25519 AAAA... orchard@host}
     * @return raw YAML beginning with {@code #cloud-config\n}
     * @throws IllegalArgumentException if {@code publicKey} is null or blank
     */
    public static String render(SeedlingSpec spec, String publicKey) {
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalArgumentException("publicKey must not be null or blank");
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
                runcmd:
                  - systemctl enable --now docker
                  - usermod -aG docker cultivator
                  - mkdir -p /workspace
                  - chown cultivator:cultivator /workspace
                """.formatted(publicKey);
    }

    /**
     * Renders cloud-init YAML and Base64-encodes it for use as
     * {@link software.amazon.awssdk.services.ec2.model.RunInstancesRequest#userData()}.
     */
    public static String renderBase64(SeedlingSpec spec, String publicKey) {
        String yaml = render(spec, publicKey);
        return Base64.getEncoder().encodeToString(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
