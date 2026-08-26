package dev.orchard.nursery;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.vine.SshVine;
import dev.orchard.vine.Vine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VmGroveProviderTest {

    @Mock SeedlingProvider seedlingProvider;
    @Mock FruitGrower fruitGrower;

    private VmGroveProvider provider() {
        return new VmGroveProvider(seedlingProvider, fruitGrower,
            new DevcontainerCliConfig("0.80.0", 600, 60));
    }

    @Test
    void plantSubstrate_delegatesToTheSeedlingProvider() {
        Seedling s = TestSeedlings.fake();
        when(seedlingProvider.plant(s)).thenReturn(CompletableFuture.completedFuture(s));

        assertThat(provider().plantSubstrate(s).join()).isSameAs(s);
        verify(seedlingProvider).plant(s);
    }

    @Test
    void growFruit_delegatesToTheFruitGrower() {
        Seedling s = TestSeedlings.fake();
        // Seed must be non-null: Fruit.bud dereferences seed.name() at Fruit.java:40.
        Fruit f = Fruit.bud(s.groveId(), s.id(),
            DevcontainerSeed.builder().name("test").image("alpine:3").build());
        when(fruitGrower.grow(s, f)).thenReturn(CompletableFuture.completedFuture(f));

        assertThat(provider().growFruit(s, f).join()).isSameAs(f);
        verify(fruitGrower).grow(s, f);
    }

    @Test
    void getProviderId_reportsTheWrappedProvidersId() {
        when(seedlingProvider.getProviderId()).thenReturn("qemu-local");

        assertThat(provider().getProviderId()).isEqualTo("qemu-local");
    }

    @Test
    void vine_isSshBackedForVmSubstrates() {
        assertThat(provider().vine(TestSeedlings.fake())).isInstanceOf(SshVine.class);
    }

    @Test
    void vine_isAvailableBeforeTheSubstrateIsRoutable() {
        // A GERMINATING seedling has no endpoint yet. Obtaining a vine must not require one:
        // provisioning and diagnostic exec both run before the grove is routable (#215).
        Seedling germinating = Seedling.germinate(TestSeedlings.fake().groveId(),
            Seedling.SeedlingSpec.small());

        assertThat(germinating.state()).isEqualTo(SeedlingState.GERMINATING);
        assertThat(germinating.ipAddress()).isNull();

        // Must not throw, and must yield a usable command channel — not merely a non-null object.
        Vine vine = provider().vine(germinating);

        assertThat(vine).isNotNull();
        assertThat(vine.commands()).isNotNull();
    }
}
