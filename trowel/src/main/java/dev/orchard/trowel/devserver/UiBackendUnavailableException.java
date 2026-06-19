package dev.orchard.trowel.devserver;

/** Thrown when no orchard-ui-backend binary is available and none could be downloaded. */
public class UiBackendUnavailableException extends Exception {
    public UiBackendUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
