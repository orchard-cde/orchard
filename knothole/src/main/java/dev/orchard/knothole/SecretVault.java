package dev.orchard.knothole;

import java.util.Optional;
import java.util.Set;

public interface SecretVault {

    void put(String key, byte[] value);

    Optional<byte[]> get(String key);

    /** Returns false if the key was absent. */
    boolean delete(String key);

    Set<String> keys();

    /** Backend-specific stale-artifact cleanup; returns the number of artifacts removed. */
    default int purge() {
        return 0;
    }
}
