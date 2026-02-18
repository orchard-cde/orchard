package dev.orchard.nursery.qemu;

import java.nio.file.Path;

/**
 * Configuration for the QEMU seedling provider.
 */
public record QemuConfig(
    Path qemuBinary,
    Path qemuImgBinary,
    Path baseImagePath,
    Path vmStoragePath,
    Path cloudInitTemplatePath,
    int sshPortRangeStart,
    int sshPortRangeEnd,
    int vncPortRangeStart,
    boolean enableKvm
) {
    public static QemuConfig defaults() {
        return new QemuConfig(
            Path.of("/usr/bin/qemu-system-x86_64"),
            Path.of("/usr/bin/qemu-img"),
            Path.of("/var/lib/orchard/images/base.qcow2"),
            Path.of("/var/lib/orchard/vms"),
            Path.of("/etc/orchard/cloud-init"),
            10022,
            10122,
            5900,
            true
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Path qemuBinary = Path.of("/usr/bin/qemu-system-x86_64");
        private Path qemuImgBinary = Path.of("/usr/bin/qemu-img");
        private Path baseImagePath = Path.of("/var/lib/orchard/images/base.qcow2");
        private Path vmStoragePath = Path.of("/var/lib/orchard/vms");
        private Path cloudInitTemplatePath = Path.of("/etc/orchard/cloud-init");
        private int sshPortRangeStart = 10022;
        private int sshPortRangeEnd = 10122;
        private int vncPortRangeStart = 5900;
        private boolean enableKvm = true;

        public Builder qemuBinary(Path path) { this.qemuBinary = path; return this; }
        public Builder qemuImgBinary(Path path) { this.qemuImgBinary = path; return this; }
        public Builder baseImagePath(Path path) { this.baseImagePath = path; return this; }
        public Builder vmStoragePath(Path path) { this.vmStoragePath = path; return this; }
        public Builder cloudInitTemplatePath(Path path) { this.cloudInitTemplatePath = path; return this; }
        public Builder sshPortRangeStart(int port) { this.sshPortRangeStart = port; return this; }
        public Builder sshPortRangeEnd(int port) { this.sshPortRangeEnd = port; return this; }
        public Builder vncPortRangeStart(int port) { this.vncPortRangeStart = port; return this; }
        public Builder enableKvm(boolean enable) { this.enableKvm = enable; return this; }

        public QemuConfig build() {
            return new QemuConfig(qemuBinary, qemuImgBinary, baseImagePath, vmStoragePath,
                cloudInitTemplatePath, sshPortRangeStart, sshPortRangeEnd, vncPortRangeStart, enableKvm);
        }
    }
}
