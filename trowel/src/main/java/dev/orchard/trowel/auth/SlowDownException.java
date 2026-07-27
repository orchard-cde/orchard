package dev.orchard.trowel.auth;

public class SlowDownException extends FenceAuthException {
    public SlowDownException() {
        super("slow down");
    }
}
