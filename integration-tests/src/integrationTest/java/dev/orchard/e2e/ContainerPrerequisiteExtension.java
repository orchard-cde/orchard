package dev.orchard.e2e;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Skips container E2E tests when a container engine or the pinned devcontainer CLI is not
 * available. With {@code -Dorchard.e2e.require-container=true} a missing prerequisite fails the
 * class instead. Diagnostics go to stderr with {@link #OUTPUT_PREFIX} so a skip names its reason.
 */
public class ContainerPrerequisiteExtension implements ExecutionCondition {

    static final String REQUIRE_CONTAINER_PROPERTY = "orchard.e2e.require-container";
    static final String OUTPUT_PREFIX = "[container-prerequisites] ";
    static final String DEVCONTAINER_CLI_VERSION = "0.87.0";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        List<String> missing = new ArrayList<>();

        if (!succeeds("docker", "info") && !succeeds("podman", "info")) {
            missing.add("No reachable container engine: both `docker info` and `podman info` failed");
        }

        String version = output("devcontainer", "--version");
        if (version == null) {
            missing.add("devcontainer CLI not found (install via: npm install -g @devcontainers/cli@"
                + DEVCONTAINER_CLI_VERSION + ")");
        } else if (!DEVCONTAINER_CLI_VERSION.equals(version)) {
            missing.add("devcontainer CLI version " + version + " does not match the pinned "
                + DEVCONTAINER_CLI_VERSION + " (install via: npm install -g @devcontainers/cli@"
                + DEVCONTAINER_CLI_VERSION + ")");
        }

        if (missing.isEmpty()) {
            return ConditionEvaluationResult.enabled("All container prerequisites are available");
        }

        boolean required = Boolean.getBoolean(REQUIRE_CONTAINER_PROPERTY);
        String reason = "Container prerequisites missing — " + (required ? "failing " : "skipping ")
            + context.getRequiredTestClass().getSimpleName() + ":\n  - " + String.join("\n  - ", missing);
        reason.lines().forEach(line -> System.err.println(OUTPUT_PREFIX + line));
        if (required) {
            throw new IllegalStateException(reason + "\n(" + REQUIRE_CONTAINER_PROPERTY + "=true)");
        }
        return ConditionEvaluationResult.disabled(reason);
    }

    private static boolean succeeds(String... command) {
        return output(command) != null;
    }

    private static String output(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String out = new String(process.getInputStream().readAllBytes()).trim();
            return process.exitValue() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }
}
