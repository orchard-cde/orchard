package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderTypeTest {

    @Test
    void idsMatchTheExistingProviderIdStrings() {
        assertThat(ProviderType.QEMU_LOCAL.id()).isEqualTo("qemu-local");
        assertThat(ProviderType.AWS_EC2.id()).isEqualTo("aws-ec2");
        assertThat(ProviderType.AZURE_VM.id()).isEqualTo("azure-vm");
        assertThat(ProviderType.GCP_COMPUTE.id()).isEqualTo("gcp-compute");
    }
}
