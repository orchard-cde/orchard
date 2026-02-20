package dev.orchard.nursery;

import dev.orchard.core.model.Seedling;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private static class StubProvider implements SeedlingProvider {
        private final String providerId;

        StubProvider(String providerId) {
            this.providerId = providerId;
        }

        @Override public String getProviderId() { return providerId; }
        @Override public boolean isAvailable() { return true; }
        @Override public CompletableFuture<Seedling> plant(Seedling s) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Seedling> water(Seedling s) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Seedling> dormant(Seedling s) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Void> uproot(Seedling s) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<Seedling> inspect(Seedling s) { throw new UnsupportedOperationException(); }
    }
}
