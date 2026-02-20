package dev.orchard.core.model;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrebuildTest {

    @Test
    void create_setsSproutingState() {
        Prebuild prebuild = Prebuild.create("https://github.com/test/repo", "main");
        assertThat(prebuild.state()).isEqualTo(PrebuildState.SPROUTING);
    }

    @Test
    void create_setsRepositoryAndBranch() {
        Prebuild prebuild = Prebuild.create("https://github.com/test/repo", "main");
        assertThat(prebuild.repositoryUrl()).isEqualTo("https://github.com/test/repo");
        assertThat(prebuild.branch()).isEqualTo("main");
    }

    @Test
    void create_setsNullCommitShaAndImageRef() {
        Prebuild prebuild = Prebuild.create("https://github.com/test/repo", "main");
        assertThat(prebuild.commitSha()).isNull();
        assertThat(prebuild.imageRef()).isNull();
    }

    @Test
    void create_setsCreatedAtAndNullCompletedAt() {
        Instant before = Instant.now();
        Prebuild prebuild = Prebuild.create("https://github.com/test/repo", "main");
        Instant after = Instant.now();

        assertThat(prebuild.createdAt()).isBetween(before, after);
        assertThat(prebuild.completedAt()).isNull();
    }

    @Test
    void withState_toRipe_setsCompletedAt() {
        Prebuild prebuild = Prebuild.create("https://github.com/test/repo", "main");
        Instant before = Instant.now();
        Prebuild updated = prebuild.withState(PrebuildState.RIPE);
        Instant after = Instant.now();

        assertThat(updated.state()).isEqualTo(PrebuildState.RIPE);
        assertThat(updated.completedAt()).isBetween(before, after);
    }

    @Test
    void withState_toFailed_doesNotSetCompletedAt() {
        Prebuild prebuild = Prebuild.create("https://github.com/test/repo", "main");
        Prebuild updated = prebuild.withState(PrebuildState.FAILED);

        assertThat(updated.state()).isEqualTo(PrebuildState.FAILED);
        assertThat(updated.completedAt()).isNull();
    }

    @Test
    void withCommitSha_setsCommitSha() {
        Prebuild prebuild = Prebuild.create("https://github.com/test/repo", "main");
        Prebuild updated = prebuild.withCommitSha("abc123");
        assertThat(updated.commitSha()).isEqualTo("abc123");
    }

    @Test
    void withImageRef_setsImageRef() {
        Prebuild prebuild = Prebuild.create("https://github.com/test/repo", "main");
        Prebuild updated = prebuild.withImageRef("ghcr.io/test/repo:prebuild-abc123");
        assertThat(updated.imageRef()).isEqualTo("ghcr.io/test/repo:prebuild-abc123");
    }

    @Test
    void isUsable_trueWhenRipeWithImageRef() {
        Prebuild prebuild = Prebuild.create("https://github.com/test/repo", "main")
                .withImageRef("ghcr.io/test/repo:prebuild")
                .withState(PrebuildState.RIPE);
        assertThat(prebuild.isUsable()).isTrue();
    }

    @Test
    void isUsable_falseWhenRipeWithoutImageRef() {
        Prebuild prebuild = Prebuild.create("https://github.com/test/repo", "main")
                .withState(PrebuildState.RIPE);
        assertThat(prebuild.isUsable()).isFalse();
    }

    @Test
    void isUsable_falseWhenNotRipe() {
        Prebuild prebuild = Prebuild.create("https://github.com/test/repo", "main")
                .withImageRef("ghcr.io/test/repo:prebuild");
        assertThat(prebuild.isUsable()).isFalse();
    }
}
