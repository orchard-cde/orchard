package dev.orchard.vine;

import java.util.UUID;

/**
 * {@link Vine} for VM-backed groves: reaches the substrate over SSH.
 *
 * <p>Holds one {@link SshExecutor} for the target's lifetime rather than building one per call —
 * the diagnostic paths call {@link #commands()} repeatedly.
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
