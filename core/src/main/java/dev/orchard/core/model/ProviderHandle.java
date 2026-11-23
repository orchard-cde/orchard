package dev.orchard.core.model;

/** The provider's own handle for a substrate: an EC2 instance id, QEMU's plot id, a container id. */
public record ProviderHandle(String value) {}
