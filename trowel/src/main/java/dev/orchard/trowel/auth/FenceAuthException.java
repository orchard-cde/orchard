package dev.orchard.trowel.auth;

import java.io.IOException;

public class FenceAuthException extends IOException {
    public FenceAuthException(String message) {
        super(message);
    }
}
