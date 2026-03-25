package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CultivatorTest {

    @Test
    void create_setsUsernameAndEmail() {
        Cultivator cultivator = Cultivator.create("alice", "alice@example.com");
        assertThat(cultivator.username()).isEqualTo("alice");
        assertThat(cultivator.email()).isEqualTo("alice@example.com");
    }

    @Test
    void create_setsOidcProvider() {
        Cultivator cultivator = Cultivator.create("alice", "alice@example.com");
        assertThat(cultivator.provider()).isEqualTo("oidc");
    }

    @Test
    void create_generatesUniqueId() {
        Cultivator c1 = Cultivator.create("alice", "alice@example.com");
        Cultivator c2 = Cultivator.create("bob", "bob@example.com");
        assertThat(c1.id()).isNotEqualTo(c2.id());
    }

    @Test
    void create_setsTimestamps() {
        Instant before = Instant.now();
        Cultivator cultivator = Cultivator.create("alice", "alice@example.com");
        Instant after = Instant.now();

        assertThat(cultivator.createdAt()).isBetween(before, after);
        assertThat(cultivator.lastActiveAt()).isBetween(before, after);
    }

    @Test
    void create_setsNullOptionalFields() {
        Cultivator cultivator = Cultivator.create("alice", "alice@example.com");
        assertThat(cultivator.providerId()).isNull();
        assertThat(cultivator.avatarUrl()).isNull();
        assertThat(cultivator.displayName()).isNull();
    }

    @Test
    void createFromOAuth_setsAllFields() {
        Cultivator cultivator = Cultivator.createFromOAuth(
            "github", "gh-123", "alice", "alice@example.com",
            "https://avatar.url", "Alice Smith");

        assertThat(cultivator.provider()).isEqualTo("github");
        assertThat(cultivator.providerId()).isEqualTo("gh-123");
        assertThat(cultivator.username()).isEqualTo("alice");
        assertThat(cultivator.email()).isEqualTo("alice@example.com");
        assertThat(cultivator.avatarUrl()).isEqualTo("https://avatar.url");
        assertThat(cultivator.displayName()).isEqualTo("Alice Smith");
        assertThat(cultivator.id()).isNotNull();
        assertThat(cultivator.createdAt()).isNotNull();
    }
}
