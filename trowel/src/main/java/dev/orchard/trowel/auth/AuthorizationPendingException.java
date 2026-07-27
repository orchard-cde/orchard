package dev.orchard.trowel.auth;

public class AuthorizationPendingException extends FenceAuthException {
    public AuthorizationPendingException() {
        super("authorization pending");
    }
}
