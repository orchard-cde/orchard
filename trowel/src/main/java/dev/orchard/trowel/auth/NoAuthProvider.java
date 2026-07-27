package dev.orchard.trowel.auth;

public class NoAuthProvider implements AuthProvider {
    @Override
    public String authorizationHeader() {
        return null;
    }
}
