package dev.orchard.nursery.qemu;

import java.nio.file.Path;

/**
 * Static utility class providing platform-aware defaults for QEMU configuration.
 * Detects the current OS (macOS vs Linux) and CPU architecture (aarch64 vs x86_64)
 * to return sensible default paths, flags, and URLs for QEMU-based VM provisioning.
 */
public final class QemuPlatformDefaults {

    private QemuPlatformDefaults() {
        // utility class — no instances
    }

    /**
     * Returns {@code true} if the current OS is macOS.
     */
    public static boolean isMacOS() {
        return System.getProperty("os.name", "").contains("Mac");
    }

    /**
     * Returns {@code true} if the current CPU architecture is aarch64 (ARM64).
     */
    public static boolean isAarch64() {
        String arch = System.getProperty("os.arch", "");
        return arch.equals("aarch64") || arch.equals("arm64");
    }

    /**
     * Returns the default path to the {@code qemu-system-*} binary for the current platform.
     * <ul>
     *   <li>macOS + aarch64: {@code /opt/homebrew/bin/qemu-system-aarch64}</li>
     *   <li>macOS + x86_64:  {@code /opt/homebrew/bin/qemu-system-x86_64}</li>
     *   <li>Linux:           {@code /usr/bin/qemu-system-x86_64}</li>
     * </ul>
     */
    public static Path defaultQemuBinary() {
        if (isMacOS()) {
            return isAarch64()
                ? Path.of("/opt/homebrew/bin/qemu-system-aarch64")
                : Path.of("/opt/homebrew/bin/qemu-system-x86_64");
        }
        return Path.of("/usr/bin/qemu-system-x86_64");
    }

    /**
     * Returns the default path to the {@code qemu-img} binary for the current platform.
     * <ul>
     *   <li>macOS: {@code /opt/homebrew/bin/qemu-img}</li>
     *   <li>Linux: {@code /usr/bin/qemu-img}</li>
     * </ul>
     */
    public static Path defaultQemuImgBinary() {
        return isMacOS()
            ? Path.of("/opt/homebrew/bin/qemu-img")
            : Path.of("/usr/bin/qemu-img");
    }

    /**
     * Returns the default directory used to store base VM images.
     * <ul>
     *   <li>macOS: {@code /tmp/orchard/images}</li>
     *   <li>Linux: {@code /var/lib/orchard/images}</li>
     * </ul>
     */
    public static Path defaultBaseImageDir() {
        return isMacOS()
            ? Path.of("/tmp/orchard/images")
            : Path.of("/var/lib/orchard/images");
    }

    /**
     * Returns the default path to the base QCOW2 image file
     * ({@link #defaultBaseImageDir()} + {@code /base.qcow2}).
     */
    public static Path defaultBaseImagePath() {
        return defaultBaseImageDir().resolve("base.qcow2");
    }

    /**
     * Returns the default directory used for per-VM storage.
     * <ul>
     *   <li>macOS: {@code /tmp/orchard/vms}</li>
     *   <li>Linux: {@code /var/lib/orchard/vms}</li>
     * </ul>
     */
    public static Path defaultVmStoragePath() {
        return isMacOS()
            ? Path.of("/tmp/orchard/vms")
            : Path.of("/var/lib/orchard/vms");
    }

    /**
     * Returns whether KVM hardware acceleration should be enabled by default.
     * macOS uses Apple Hypervisor Framework (HVF) instead of KVM, so this returns
     * {@code false} on macOS and {@code true} on Linux.
     */
    public static boolean defaultEnableKvm() {
        return !isMacOS();
    }

    /**
     * Returns the default serial output mode.
     * <ul>
     *   <li>macOS: {@code "file"} — avoids stdio conflicts with macOS terminal handling</li>
     *   <li>Linux: {@code "stdio"}</li>
     * </ul>
     */
    public static String defaultSerialOutput() {
        return isMacOS() ? "file" : "stdio";
    }

    /**
     * Returns the default path to the Orchard SSH key pair
     * ({@code ~/.ssh/orchard_ed25519}).
     */
    public static Path defaultSshKeyPath() {
        return Path.of(System.getProperty("user.home"), ".ssh", "orchard_ed25519");
    }

    /**
     * Returns the Ubuntu Jammy cloud image download URL appropriate for the current architecture.
     * <ul>
     *   <li>aarch64: ARM64 image</li>
     *   <li>x86_64:  AMD64 image</li>
     * </ul>
     */
    public static String ubuntuImageUrl() {
        return isAarch64()
            ? "https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-arm64.img"
            : "https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-amd64.img";
    }
}
