package dev.orchard.knothole;

public final class VaultException extends RuntimeException {

    VaultException(String message, Throwable cause) {
        super(message, cause);
    }

    VaultException(String message) {
        super(message);
    }
}
