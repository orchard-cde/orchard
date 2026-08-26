package dev.orchard.nursery.qemu;

import dev.orchard.core.model.Seedling;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The external commands QEMU planting shells out to, behind an interface so {@code plant()} can be
 * tested without a hypervisor. Production use is {@link DefaultQemuCommands}.
 */
public interface QemuCommands {

    void createDiskImage(Path image, int diskGb) throws IOException, InterruptedException;

    void createCloudInitIso(Path iso, Seedling seedling) throws IOException, InterruptedException;

    int allocateSshPort() throws IOException;

    Process startQemu(Seedling seedling, Path diskImage, Path cloudInitIso, int sshPort) throws IOException;
}
