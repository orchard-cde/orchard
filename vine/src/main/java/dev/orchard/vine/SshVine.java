package dev.orchard.vine;

import java.util.UUID;

/**
 * {@link Vine} for VM-backed groves: reaches the substrate over SSH.
 *
 * <p>Builds its {@link SshExecutor} once, in the constructor. Not a cache that anything depends
 * on: callers build a fresh vine per operation and call {@link #commands()} exactly once, because
 * a vine resolves a substrate's current endpoint and holding one risks a stale address (see
 * {@code AbstractGroveProvider.vine}). The field is simply cheaper than re-deriving it.
 */
public final class SshVine implements Vine {

    private final CommandRunner runner;

    public SshVine(String host, int port, UUID targetId) {
        this.runner = new SshExecutor(host, port, targetId);
    }

    @Override
    public CommandRunner commands() {
        return runner;
    }
}
