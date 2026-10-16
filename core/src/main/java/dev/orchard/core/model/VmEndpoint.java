package dev.orchard.core.model;

/**
 * Where a launched VM lives and what its provider calls it. Identity and address are distinct
 * types so they cannot be transposed.
 */
public record VmEndpoint(InstanceId instanceId, Host host, int sshPort) {

    public record InstanceId(String value) {}

    public record Host(String value) {}
}
