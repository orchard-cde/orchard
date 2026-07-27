package dev.orchard.trowel.auth;

public class InvalidGrantException extends FenceAuthException {
    public InvalidGrantException() {
        super("invalid_grant");
    }
}
