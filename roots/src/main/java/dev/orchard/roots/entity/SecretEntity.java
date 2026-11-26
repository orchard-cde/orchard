package dev.orchard.roots.entity;

import dev.orchard.core.model.Secret;
import dev.orchard.core.model.SecretScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "secrets", uniqueConstraints = {
    @UniqueConstraint(name = "uq_secrets_cultivator_name", columnNames = {"cultivator_id", "name"}),
    @UniqueConstraint(name = "uq_secrets_owner_name", columnNames = {"owner", "name"})
})
public class SecretEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SecretScope scope;

    private UUID cultivatorId;

    private String owner;

    @Column(nullable = false)
    private String name;

    @Column(name = "vault_key", nullable = false, unique = true)
    private String vaultKey;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private Instant rotatedAt;

    protected SecretEntity() {}

    public static SecretEntity fromModel(Secret secret) {
        SecretEntity entity = new SecretEntity();
        entity.id = secret.id();
        entity.scope = secret.scope();
        entity.cultivatorId = secret.cultivatorId();
        entity.owner = secret.owner();
        entity.name = secret.name();
        entity.vaultKey = secret.vaultKey();
        entity.createdAt = secret.createdAt();
        entity.updatedAt = secret.updatedAt();
        entity.rotatedAt = secret.rotatedAt();
        return entity;
    }

    public Secret toModel() {
        return new Secret(id, scope, cultivatorId, owner, name, vaultKey, createdAt, updatedAt, rotatedAt);
    }

    public UUID getId() { return id; }
    public SecretScope getScope() { return scope; }
    public UUID getCultivatorId() { return cultivatorId; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public String getVaultKey() { return vaultKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getRotatedAt() { return rotatedAt; }
}
