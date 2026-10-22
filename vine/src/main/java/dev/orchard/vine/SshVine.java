package dev.orchard.vine;

import dev.orchard.core.model.VmPlot;

/**
 * {@link Vine} for VM-backed groves: reaches the substrate over SSH.
 *
 * <p>Callers build a fresh vine per operation and call {@link #commands()} once: a vine captures
 * the plot's endpoint at construction, and that endpoint changes across water/inspect cycles.
 */
public final class SshVine implements Vine {

    private final CommandRunner runner;

    public SshVine(VmPlot plot) {
        this.runner = new SshExecutor(plot.host(), plot.sshPort(), plot.id());
    }

    @Override
    public CommandRunner commands() {
        return runner;
    }
}
