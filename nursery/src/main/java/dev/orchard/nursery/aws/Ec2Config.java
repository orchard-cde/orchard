package dev.orchard.nursery.aws;

import java.util.Map;

/**
 * Configuration for the AWS EC2 seedling provider.
 *
 * @param region            AWS region (e.g., "us-east-1")
 * @param amiId             AMI ID for the base VM image (Ubuntu with Docker pre-installed)
 * @param keyPairName       EC2 key pair name for SSH access
 * @param securityGroupId   Security group allowing SSH (port 22) and application ports
 * @param subnetId          VPC subnet to launch instances into
 * @param instanceTypeMapping Maps CPU core count to EC2 instance type (e.g., 2 -> "t3.small")
 */
public record Ec2Config(
    String region,
    String amiId,
    String keyPairName,
    String securityGroupId,
    String subnetId,
    Map<Integer, String> instanceTypeMapping
) {
    /**
     * Resolves the EC2 instance type for a given CPU core count.
     * Falls back to t3.small if no mapping exists.
     */
    public String resolveInstanceType(int cpuCores) {
        return instanceTypeMapping.getOrDefault(cpuCores, "t3.small");
    }
}
