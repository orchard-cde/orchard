package dev.orchard.apiary;

import dev.orchard.core.model.BeeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BeeKeeperRegistryTest {

    private BeeKeeperRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new BeeKeeperRegistry();
    }

    @Test
    void register_andLookup() {
        BeeKeeper keeper = mock(BeeKeeper.class);
        when(keeper.getBeeType()).thenReturn(BeeType.CLAUDE_CODE);

        registry.register(keeper);

        assertThat(registry.get(BeeType.CLAUDE_CODE)).isPresent().containsSame(keeper);
    }

    @Test
    void get_unknownType_returnsEmpty() {
        assertThat(registry.get(BeeType.CLAUDE_CODE)).isEmpty();
    }

    @Test
    void isRegistered_trueAfterRegister() {
        BeeKeeper keeper = mock(BeeKeeper.class);
        when(keeper.getBeeType()).thenReturn(BeeType.GEMINI);

        registry.register(keeper);

        assertThat(registry.isRegistered(BeeType.GEMINI)).isTrue();
    }

    @Test
    void isRegistered_falseForUnregisteredType() {
        assertThat(registry.isRegistered(BeeType.CLAUDE_CODE)).isFalse();
    }

    @Test
    void register_duplicateType_replaces() {
        BeeKeeper first = mock(BeeKeeper.class);
        when(first.getBeeType()).thenReturn(BeeType.CLAUDE_CODE);
        BeeKeeper second = mock(BeeKeeper.class);
        when(second.getBeeType()).thenReturn(BeeType.CLAUDE_CODE);

        registry.register(first);
        registry.register(second);

        assertThat(registry.get(BeeType.CLAUDE_CODE)).isPresent().containsSame(second);
    }
}
