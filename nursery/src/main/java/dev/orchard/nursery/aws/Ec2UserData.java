package dev.orchard.nursery.aws;

import dev.orchard.core.model.Seedling.SeedlingSpec;
import dev.orchard.nursery.CloudInitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Renders the cloud-init {@code #cloud-config} YAML used to bootstrap an EC2 instance.
 * Pure function — no SDK calls. Loads the YAML body from the classpath template
 * {@code /cloud-init/aws.yaml.tpl} and substitutes {@code ${ssh_public_key}} /
 * {@code ${cli_version}}. The QEMU provider uses a sibling template
 * ({@code qemu.yaml.tpl}) via {@link CloudInitTemplate}.
 */
public final class Ec2UserData {

    private static final String TEMPLATE_PATH = "/cloud-init/aws.yaml.tpl";

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
        return CloudInitTemplate.render(TEMPLATE_PATH, Map.of(
            "ssh_public_key", publicKey,
            "cli_version", devcontainerCliVersion
        ));
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
