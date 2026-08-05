package dev.orchard.core.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public record SshPublicKey(
    UUID id,
    UUID cultivatorId,
    String name,
    String publicKey,
    String fingerprint,
    Instant createdAt
) {
    public static SshPublicKey register(UUID cultivatorId, String name, String publicKey) {
        if (cultivatorId == null) {
            throw new IllegalArgumentException("cultivatorId must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalArgumentException("publicKey must not be blank");
        }
        return new SshPublicKey(
            UUID.randomUUID(),
            cultivatorId,
            name.trim(),
            publicKey.trim(),
            fingerprint(publicKey.trim()),
            Instant.now()
        );
    }

    public static String fingerprint(String publicKey) {
        String[] parts = publicKey.trim().split("\\s+");
        if (parts.length < 2) {
            throw new IllegalArgumentException("publicKey must contain a key type and base64-encoded key material");
        }
        byte[] keyBlob;
        try {
            keyBlob = Base64.getDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("publicKey contains invalid base64-encoded key material", e);
        }
        byte[] digest = sha256(keyBlob);
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
