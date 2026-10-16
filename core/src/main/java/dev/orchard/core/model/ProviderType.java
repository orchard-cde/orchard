package dev.orchard.core.model;

/**
 * The provider implementations built into Orchard. A type is code, so this set only changes with a
 * new provider; configured instances of a type (#170) are separate and dynamic.
 */
public enum ProviderType {
    QEMU_LOCAL("qemu-local"),
    AWS_EC2("aws-ec2"),
    AZURE_VM("azure-vm"),
    GCP_COMPUTE("gcp-compute");

    private final String id;

    ProviderType(String id) {
        this.id = id;
    }

    /** The id used as the registry key, in configuration, and on persisted plots. */
    public String id() {
        return id;
    }
}
