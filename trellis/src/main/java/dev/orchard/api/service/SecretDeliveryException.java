package dev.orchard.api.service;

public final class SecretDeliveryException extends RuntimeException {

    public SecretDeliveryException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
