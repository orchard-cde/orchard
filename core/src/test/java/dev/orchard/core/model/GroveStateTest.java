package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroveStateTest {

    @Test
    void orphanedExistsAndIsDistinctFromCleared() {
        assertThat(GroveState.valueOf("ORPHANED")).isEqualTo(GroveState.ORPHANED);
        assertThat(GroveState.ORPHANED).isNotEqualTo(GroveState.CLEARED);
    }

    /**
     * Guards the persistence contract: state is stored as a string in a VARCHAR(50) column with no
     * CHECK constraint (V1__initial_schema.sql:21), so the constant name is the wire format and
     * must fit. Renaming ORPHANED is a schema-visible change even though no migration declares it.
     */
    @Test
    void everyConstantFitsTheVarchar50StateColumn() {
        for (GroveState s : GroveState.values()) {
            assertThat(s.name().length()).isLessThanOrEqualTo(50);
        }
    }
}
