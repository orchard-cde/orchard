package dev.orchard.nursery.qemu;

import dev.orchard.core.model.VmPlot;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The external commands QEMU planting shells out to, behind an interface so {@code plant()} can be
 * tested without a hypervisor. Production use is {@link DefaultQemuCommands}.
 */
public interface QemuCommands {

    void createDiskImage(Path image, int diskGb) throws IOException, InterruptedException;

    void createCloudInitIso(Path iso, VmPlot plot) throws IOException, InterruptedException;

    int allocateSshPort() throws IOException;

    Process startQemu(VmPlot plot, Path diskImage, Path cloudInitIso, int sshPort) throws IOException;

    void awaitReachable(String host, int sshPort) throws IOException;

    /**
     * Asks the guest to power itself off over a fresh SSH connection. Best effort: the connection
     * drops as the guest goes down, so failures are swallowed and the caller confirms exit itself.
     */
    void requestPoweroff(VmPlot plot);
}
