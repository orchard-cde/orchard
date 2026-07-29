package dev.orchard.trowel.auth;

public class AuthorizationDeniedException extends FenceAuthException {
    public AuthorizationDeniedException() {
        super("authorization denied");
    }
}
