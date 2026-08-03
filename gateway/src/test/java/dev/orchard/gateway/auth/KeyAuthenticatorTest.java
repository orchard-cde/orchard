package dev.orchard.gateway.auth;

import dev.orchard.core.model.SshPublicKey;
import dev.orchard.gateway.api.GatewayKey;
import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.TrellisApiClient;
import dev.orchard.gateway.service.GroveResolver;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeyAuthenticatorTest {

    // RSA, not Ed25519: MINA's PublicKeyEntry.toString(PublicKey) can't encode a JDK-native
    // Ed25519 key (java.security.interfaces.EdECPublicKey) without a BouncyCastle or
    // net.i2p.crypto.eddsa security-provider registrar on the classpath, and the gateway
    // module has neither. RSA round-trips through the same MINA API with no extra dependency.
    private static KeyPair testKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("RSA").generateKeyPair();
    }

    @Test
    void authenticate_acceptsRegisteredKeyAndStoresRoute() throws Exception {
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        GroveResolver resolver = mock(GroveResolver.class);
        ServerSession session = mock(ServerSession.class);

        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        GatewayRoute route = new GatewayRoute(groveId, cultivatorId, "127.0.0.1", 22, "FLOURISHING");
        when(resolver.resolve(groveId.toString())).thenReturn(Optional.of(route));

        KeyPair pair = testKeyPair();
        String wireLine = PublicKeyEntry.toString(pair.getPublic());
        String fingerprint = SshPublicKey.fingerprint(wireLine);
        when(trellis.listKeys(cultivatorId))
                .thenReturn(List.of(new GatewayKey(UUID.randomUUID(), "laptop", wireLine, fingerprint)));

        KeyAuthenticator authenticator = new KeyAuthenticator(resolver, trellis);

        assertThat(authenticator.authenticate(groveId.toString(), pair.getPublic(), session)).isTrue();
        verify(session).setAttribute(eq(KeyAuthenticator.ROUTE_KEY), eq(route));
    }

    @Test
    void authenticate_rejectsUnknownKey() throws Exception {
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        GroveResolver resolver = mock(GroveResolver.class);
        ServerSession session = mock(ServerSession.class);

        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        GatewayRoute route = new GatewayRoute(groveId, cultivatorId, "127.0.0.1", 22, "FLOURISHING");
        when(resolver.resolve(groveId.toString())).thenReturn(Optional.of(route));

        KeyPair registered = testKeyPair();
        String wireLine = PublicKeyEntry.toString(registered.getPublic());
        when(trellis.listKeys(cultivatorId))
                .thenReturn(List.of(new GatewayKey(UUID.randomUUID(), "laptop", wireLine,
                        SshPublicKey.fingerprint(wireLine))));

        KeyPair attacker = testKeyPair();
        KeyAuthenticator authenticator = new KeyAuthenticator(resolver, trellis);

        assertThat(authenticator.authenticate(groveId.toString(), attacker.getPublic(), session)).isFalse();
        verify(session, never()).setAttribute(any(), any());
    }

    @Test
    void authenticate_rejectsWhenGroveNotRoutable() {
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        GroveResolver resolver = mock(GroveResolver.class);
        ServerSession session = mock(ServerSession.class);
        when(resolver.resolve("deadbeef")).thenReturn(Optional.empty());

        assertThat(new KeyAuthenticator(resolver, trellis)
                .authenticate("deadbeef", mock(PublicKey.class), session)).isFalse();
    }

    @Test
    void authenticate_rejectsMalformedUsername() {
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        GroveResolver resolver = mock(GroveResolver.class);
        ServerSession session = mock(ServerSession.class);

        assertThat(new KeyAuthenticator(resolver, trellis)
                .authenticate("not-a-uuid", mock(PublicKey.class), session)).isFalse();
    }
}
