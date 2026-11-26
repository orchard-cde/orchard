package dev.orchard.knothole;

public final class VaultException extends RuntimeException {

    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }

    public VaultException(String message) {
        super(message);
    }
}
