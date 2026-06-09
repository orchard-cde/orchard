package dev.orchard.nursery.aws;

import java.nio.file.Path;
import java.util.Map;

/**
 * Configuration for the AWS EC2 seedling provider.
 *
 * @param region              AWS region (e.g., "us-east-1")
 * @param amiId               AMI ID for the base VM image (vanilla Ubuntu LTS recommended; cloud-init does the rest)
 * @param keyPairName         EC2 key pair name for SSH access (must match {@code sshKeyPath})
 * @param securityGroupId     Security group allowing SSH (port 22) and application ports
 * @param subnetId            VPC subnet to launch instances into
 * @param instanceTypeMapping Maps CPU core count to EC2 instance type (e.g., 2 -> "t3.small")
 * @param ipMode              How to select the instance's IP address for SSH
 * @param sshKeyPath          Path to the orchard private SSH key. The matching public key
 *                            ({@code <sshKeyPath>.pub}) is injected into cloud-init.
 *                            May be {@code null}; in that case {@code Ec2SeedlingProvider.isAvailable()}
 *                            returns false rather than throwing at construction time.
 */
public record Ec2Config(
    String region,
    String amiId,
    String keyPairName,
    String securityGroupId,
    String subnetId,
    Map<Integer, String> instanceTypeMapping,
    IpMode ipMode,
    Path sshKeyPath
) {
    /**
     * Resolves the EC2 instance type for a given CPU core count.
     * Falls back to t3.small if no mapping exists.
     */
    public String resolveInstanceType(int cpuCores) {
        return instanceTypeMapping.getOrDefault(cpuCores, "t3.small");
    }

    /**
     * Selects the IP address used for SSH connections to instances.
     */
    public enum IpMode {
        /** Prefer public IP; fall back to private if no public IP exists. */
        AUTO,
        /** Require a public IP; fail provisioning if none exists. */
        PUBLIC,
        /** Always use the private IP (orchard must run inside the VPC). */
        PRIVATE;

        /**
         * Parses a string into an {@link IpMode}, defaulting to {@link #AUTO} when
         * null or blank. Spring property binding uses this for {@code ip-mode}.
         */
        public static IpMode parse(String value) {
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            try {
                return IpMode.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Invalid ip-mode '" + value + "'. Valid values: AUTO, PUBLIC, PRIVATE", e);
            }
        }
    }
}
