package dev.orchard.trowel.auth;

import java.io.IOException;

public interface AuthProvider {
    String authorizationHeader() throws IOException, InterruptedException;
}
