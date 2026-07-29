package dev.orchard.trowel.auth;

public class DeviceCodeExpiredException extends FenceAuthException {
    public DeviceCodeExpiredException() {
        super("device code expired");
    }
}
