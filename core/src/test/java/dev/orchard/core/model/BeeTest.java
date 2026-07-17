package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BeeTest {

    private final UUID groveId = UUID.randomUUID();

    @Test
    void hatching_createsBeeInHatchingState() {
        BeeSpec spec = BeeSpec.of(BeeType.CLAUDE_CODE);
        Bee bee = Bee.hatching(groveId, spec);

        assertThat(bee.state()).isEqualTo(BeeState.HATCHING);
    }

    @Test
    void hatching_setsGroveIdAndSpec() {
        BeeSpec spec = BeeSpec.of(BeeType.GEMINI, "1.0");
        Bee bee = Bee.hatching(groveId, spec);

        assertThat(bee.groveId()).isEqualTo(groveId);
        assertThat(bee.spec()).isEqualTo(spec);
    }

    @Test
    void hatching_generatesUniqueId() {
        BeeSpec spec = BeeSpec.of(BeeType.CLAUDE_CODE);
        Bee bee1 = Bee.hatching(groveId, spec);
        Bee bee2 = Bee.hatching(groveId, spec);

        assertThat(bee1.id()).isNotEqualTo(bee2.id());
    }

    @Test
    void hatching_setsHatchedAt() {
        BeeSpec spec = BeeSpec.of(BeeType.CLAUDE_CODE);
        Instant before = Instant.now();
        Bee bee = Bee.hatching(groveId, spec);
        Instant after = Instant.now();

        assertThat(bee.hatchedAt()).isBetween(before, after);
    }

    @Test
    void hatching_setsNullProcessIdAndTimestamps() {
        BeeSpec spec = BeeSpec.of(BeeType.CLAUDE_CODE);
        Bee bee = Bee.hatching(groveId, spec);

        assertThat(bee.processId()).isNull();
        assertThat(bee.startedAt()).isNull();
        assertThat(bee.stoppedAt()).isNull();
    }

    @Test
    void withState_transitionsState() {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE));

        Bee updated = bee.withState(BeeState.HIBERNATING);

        assertThat(updated.state()).isEqualTo(BeeState.HIBERNATING);
    }

    @Test
    void withState_preservesOtherFields() {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE));

        Bee updated = bee.withState(BeeState.HIBERNATING);

        assertThat(updated.id()).isEqualTo(bee.id());
        assertThat(updated.groveId()).isEqualTo(bee.groveId());
        assertThat(updated.spec()).isEqualTo(bee.spec());
        assertThat(updated.hatchedAt()).isEqualTo(bee.hatchedAt());
    }

    @Test
    void withProcessId_setsProcessIdAndStartedAt() {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE));

        Instant before = Instant.now();
        Bee updated = bee.withProcessId("pid-123");
        Instant after = Instant.now();

        assertThat(updated.processId()).isEqualTo("pid-123");
        assertThat(updated.startedAt()).isBetween(before, after);
    }

    @Test
    void withStoppedAt_setsStoppedAt() {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE));

        Instant before = Instant.now();
        Bee updated = bee.withStoppedAt();
        Instant after = Instant.now();

        assertThat(updated.stoppedAt()).isBetween(before, after);
    }

    @Test
    void isReady_trueWhenBuzzing() {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE))
            .withState(BeeState.BUZZING)
            .withProcessId("pid-1");

        assertThat(bee.isReady()).isTrue();
    }

    @Test
    void isReady_falseWhenHatching() {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE));

        assertThat(bee.isReady()).isFalse();
    }

    @Test
    void isReady_falseWhenSmoked() {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE))
            .withState(BeeState.SMOKED);

        assertThat(bee.isReady()).isFalse();
    }
}
