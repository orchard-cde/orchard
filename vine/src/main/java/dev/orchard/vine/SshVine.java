package dev.orchard.vine;

import dev.orchard.core.model.Seedling;

/**
 * {@link Vine} for VM-backed groves: reaches the substrate over SSH.
 *
 * <p>Holds one {@link SshExecutor} for the seedling's lifetime rather than building one per call —
 * the diagnostic paths call {@link #commands()} repeatedly.
 */
public final class SshVine implements Vine {

    private final CommandRunner runner;

    public SshVine(Seedling seedling) {
        this.runner = new SshExecutor(seedling);
    }

    @Override
    public CommandRunner commands() {
        return runner;
    }
}
