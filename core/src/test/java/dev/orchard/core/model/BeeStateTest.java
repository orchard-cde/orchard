package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BeeStateTest {

    @Test
    void hatchTransition_toHibernating() {
        assertThat(BeeState.HATCHING.canTransitionTo(BeeState.HIBERNATING)).isTrue();
    }

    @Test
    void hatchTransition_toSmoked() {
        assertThat(BeeState.HATCHING.canTransitionTo(BeeState.SMOKED)).isTrue();
    }

    @Test
    void hatchTransition_toBuzzing_invalid() {
        assertThat(BeeState.HATCHING.canTransitionTo(BeeState.BUZZING)).isFalse();
    }

    @Test
    void hibernating_toBuzzing() {
        assertThat(BeeState.HIBERNATING.canTransitionTo(BeeState.BUZZING)).isTrue();
    }

    @Test
    void hibernating_toSmoked() {
        assertThat(BeeState.HIBERNATING.canTransitionTo(BeeState.SMOKED)).isTrue();
    }

    @Test
    void buzzing_toPollinating() {
        assertThat(BeeState.BUZZING.canTransitionTo(BeeState.POLLINATING)).isTrue();
    }

    @Test
    void buzzing_toHibernating() {
        assertThat(BeeState.BUZZING.canTransitionTo(BeeState.HIBERNATING)).isTrue();
    }

    @Test
    void buzzing_toSmoked() {
        assertThat(BeeState.BUZZING.canTransitionTo(BeeState.SMOKED)).isTrue();
    }

    @Test
    void pollinating_toBuzzing() {
        assertThat(BeeState.POLLINATING.canTransitionTo(BeeState.BUZZING)).isTrue();
    }

    @Test
    void pollinating_toSmoked() {
        assertThat(BeeState.POLLINATING.canTransitionTo(BeeState.SMOKED)).isTrue();
    }

    @Test
    void anyState_canTransitionToSmoked() {
        for (BeeState state : BeeState.values()) {
            assertThat(state.canTransitionTo(BeeState.SMOKED))
                .as("%s should be able to transition to SMOKED", state)
                .isTrue();
        }
    }

    @Test
    void smoked_isTerminal() {
        assertThat(BeeState.SMOKED.canTransitionTo(BeeState.HATCHING)).isFalse();
        assertThat(BeeState.SMOKED.canTransitionTo(BeeState.HIBERNATING)).isFalse();
        assertThat(BeeState.SMOKED.canTransitionTo(BeeState.BUZZING)).isFalse();
        assertThat(BeeState.SMOKED.canTransitionTo(BeeState.POLLINATING)).isFalse();
    }
}
