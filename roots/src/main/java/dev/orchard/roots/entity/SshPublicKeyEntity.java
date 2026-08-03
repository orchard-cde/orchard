package dev.orchard.roots.entity;

import dev.orchard.core.model.SshPublicKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ssh_public_keys")
public class SshPublicKeyEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID cultivatorId;

    @Column(nullable = false)
    private String name;

    @Column(name = "public_key", nullable = false)
    private String publicKey;

    @Column(nullable = false)
    private String fingerprint;

    @Column(nullable = false)
    private Instant createdAt;

    protected SshPublicKeyEntity() {}

    public static SshPublicKeyEntity fromModel(SshPublicKey key) {
        SshPublicKeyEntity entity = new SshPublicKeyEntity();
        entity.id = key.id();
        entity.cultivatorId = key.cultivatorId();
        entity.name = key.name();
        entity.publicKey = key.publicKey();
        entity.fingerprint = key.fingerprint();
        entity.createdAt = key.createdAt();
        return entity;
    }

    public SshPublicKey toModel() {
        return new SshPublicKey(id, cultivatorId, name, publicKey, fingerprint, createdAt);
    }

    public UUID getId() { return id; }
    public UUID getCultivatorId() { return cultivatorId; }
    public String getName() { return name; }
    public String getPublicKey() { return publicKey; }
    public String getFingerprint() { return fingerprint; }
    public Instant getCreatedAt() { return createdAt; }
}
