package dev.orchard.e2e;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JUnit 5 extension that skips E2E tests when QEMU prerequisites are not available.
 * Checks for QEMU binaries, base VM image, and SSH keys.
 */
public class QemuPrerequisiteExtension implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        List<String> missing = new ArrayList<>();

        Path qemuBinary = resolveQemuBinary();
        if (!Files.isExecutable(qemuBinary)) {
            missing.add("QEMU binary not found at " + qemuBinary
                + " (install via: brew install qemu on macOS, apt install qemu-system on Linux)");
        }

        Path qemuImg = resolveQemuImgBinary();
        if (!Files.isExecutable(qemuImg)) {
            missing.add("qemu-img binary not found at " + qemuImg);
        }

        Path baseImage = Path.of(
            System.getProperty("orchard.qemu.base-image-path", "/tmp/orchard/images/ubuntu-22.04-base.qcow2"));
        if (!Files.exists(baseImage)) {
            missing.add("Base VM image not found at " + baseImage
                + " (run scripts/setup-base-image.sh)");
        }

        Path sshDir = Path.of(System.getProperty("user.home"), ".ssh");
        Path privateKey = sshDir.resolve("orchard_ed25519");
        Path publicKey = sshDir.resolve("orchard_ed25519.pub");
        if (!Files.exists(privateKey) || !Files.exists(publicKey)) {
            missing.add("SSH key pair not found at " + privateKey
                + " (generate with: ssh-keygen -t ed25519 -f ~/.ssh/orchard_ed25519 -N '')");
        }

        if (hasIsoTool()) {
            // ISO tool is available
        } else {
            missing.add("No ISO creation tool found (install genisoimage or mkisofs)");
        }

        if (missing.isEmpty()) {
            return ConditionEvaluationResult.enabled("All QEMU prerequisites are available");
        }

        return ConditionEvaluationResult.disabled(
            "QEMU prerequisites missing — skipping E2E tests:\n  - "
                + String.join("\n  - ", missing));
    }

    private Path resolveQemuBinary() {
        String override = System.getProperty("orchard.qemu.qemu-binary");
        if (override != null) {
            return Path.of(override);
        }
        boolean isArm = System.getProperty("os.arch", "").contains("aarch64");
        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        String arch = isArm ? "aarch64" : "x86_64";
        String binDir = isMac ? "/opt/homebrew/bin" : "/usr/bin";
        return Path.of(binDir, "qemu-system-" + arch);
    }

    private Path resolveQemuImgBinary() {
        String override = System.getProperty("orchard.qemu.qemu-img-binary");
        if (override != null) {
            return Path.of(override);
        }
        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        return Path.of(isMac ? "/opt/homebrew/bin/qemu-img" : "/usr/bin/qemu-img");
    }

    private boolean hasIsoTool() {
        return isExecutableOnPath("genisoimage") || isExecutableOnPath("mkisofs");
    }

    private boolean isExecutableOnPath(String command) {
        try {
            Process p = new ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
