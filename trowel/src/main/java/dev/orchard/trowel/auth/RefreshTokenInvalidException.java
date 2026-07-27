package dev.orchard.trowel.auth;

public class RefreshTokenInvalidException extends FenceAuthException {
    public RefreshTokenInvalidException() {
        super("Session expired. Run 'trowel login' to reauthenticate.");
    }
}
