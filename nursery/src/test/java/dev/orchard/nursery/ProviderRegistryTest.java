package dev.orchard.nursery;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotRequest;
import dev.orchard.core.model.PlotSizes;
import dev.orchard.vine.Vine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRegistryTest {

    private ProviderRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ProviderRegistry();
    }

    @Test
    void register_addsProviderAndSetsDefault() {
        StubProvider provider = new StubProvider("qemu");
        registry.register(provider);

        assertThat(registry.hasProvider("qemu")).isTrue();
        assertThat(registry.getDefaultProviderId()).isEqualTo("qemu");
    }

    @Test
    void register_secondProvider_doesNotChangeDefault() {
        registry.register(new StubProvider("qemu"));
        registry.register(new StubProvider("aws"));

        assertThat(registry.getDefaultProviderId()).isEqualTo("qemu");
        assertThat(registry.hasProvider("aws")).isTrue();
    }

    @Test
    void setDefault_changesDefaultProvider() {
        registry.register(new StubProvider("qemu"));
        registry.register(new StubProvider("aws"));

        registry.setDefault("aws");

        assertThat(registry.getDefaultProviderId()).isEqualTo("aws");
    }

    @Test
    void setDefault_throwsForUnknownProvider() {
        registry.register(new StubProvider("qemu"));

        assertThatThrownBy(() -> registry.setDefault("unknown"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown");
    }

    @Test
    void getDefault_returnsDefaultProvider() {
        StubProvider provider = new StubProvider("qemu");
        registry.register(provider);

        assertThat(registry.getDefault()).isSameAs(provider);
    }

    @Test
    void getDefault_throwsWhenEmpty() {
        assertThatThrownBy(() -> registry.getDefault())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void get_returnsProviderById() {
        StubProvider provider = new StubProvider("qemu");
        registry.register(provider);

        assertThat(registry.get("qemu")).isPresent().containsSame(provider);
    }

    @Test
    void get_returnsEmptyForUnknown() {
        assertThat(registry.get("nonexistent")).isEmpty();
    }

    @Test
    void hasProvider_trueForRegistered() {
        registry.register(new StubProvider("qemu"));
        assertThat(registry.hasProvider("qemu")).isTrue();
    }

    @Test
    void hasProvider_falseForUnregistered() {
        assertThat(registry.hasProvider("qemu")).isFalse();
    }

    @Test
    void getProviderIds_returnsAllRegistered() {
        registry.register(new StubProvider("qemu"));
        registry.register(new StubProvider("aws"));
        registry.register(new StubProvider("gcp"));

        assertThat(registry.getProviderIds()).containsExactlyInAnyOrder("qemu", "aws", "gcp");
    }

    @Test
    void register_acceptsGroveProviders() {
        GroveProvider provider = new StubProvider("qemu-local");

        registry.register(provider);

        assertThat(registry.getDefault()).isSameAs(provider);
        assertThat(registry.get("qemu-local")).contains(provider);
    }

    @Test
    void sizes_defaultsToTheDefaultCatalogue() {
        assertThat(new StubProvider("x").sizes()).isSameAs(PlotSizes.DEFAULT);
    }

    private static class StubProvider implements GroveProvider {
        private final String providerId;

        StubProvider(String providerId) {
            this.providerId = providerId;
        }

        @Override public String getProviderId() { return providerId; }
        @Override public boolean isAvailable() { return true; }
        @Override public Plot reserve(UUID groveId, PlotRequest r) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Plot> plantSubstrate(Plot s) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Plot> water(Plot s) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Plot> dormant(Plot s) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Void> uproot(Plot s) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Plot> inspect(Plot s) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Fruit> growFruit(Plot s, Fruit f) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Void> compostFruit(Plot s, Fruit f) { throw new UnsupportedOperationException(); }
        @Override public Vine vine(Plot s) { throw new UnsupportedOperationException(); }
        @Override public void verifyDevcontainerCli(Plot s, String expectedVersion) { throw new UnsupportedOperationException(); }
    }
}
