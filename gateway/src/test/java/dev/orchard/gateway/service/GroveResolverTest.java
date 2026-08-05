package dev.orchard.gateway.service;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.TrellisApiClient;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GroveResolverTest {

    @Test
    void resolve_delegatesForValidUuid() {
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        UUID groveId = UUID.randomUUID();
        GatewayRoute route = new GatewayRoute(groveId, UUID.randomUUID(), "127.0.0.1", 22, "FLOURISHING");
        when(trellis.resolveGrove(groveId)).thenReturn(Optional.of(route));

        GroveResolver resolver = new GroveResolver(trellis);
        assertThat(resolver.resolve(groveId.toString())).isPresent();
        verify(trellis).resolveGrove(groveId);
    }

    @Test
    void resolve_emptyForNonRoutable() {
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        UUID groveId = UUID.randomUUID();
        when(trellis.resolveGrove(groveId)).thenReturn(Optional.empty());

        assertThat(new GroveResolver(trellis).resolve(groveId.toString())).isEmpty();
    }

    @Test
    void resolve_emptyForMalformedUsername() {
        TrellisApiClient trellis = mock(TrellisApiClient.class);

        assertThat(new GroveResolver(trellis).resolve("not-a-uuid")).isEmpty();
        assertThat(new GroveResolver(trellis).resolve("")).isEmpty();
        assertThat(new GroveResolver(trellis).resolve(null)).isEmpty();
        verifyNoInteractions(trellis);
    }
}
