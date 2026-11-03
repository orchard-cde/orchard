package dev.orchard.e2e;

import dev.orchard.nursery.container.ContainerRuntimes;
import dev.orchard.vine.LocalCommandRunner;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Skips container E2E tests when a container engine or the pinned devcontainer CLI is not
 * available. The pinned CLI version is read from the product's {@code application.yml}. With {@code -Dorchard.e2e.require-container=true} a missing prerequisite fails the
 * class instead. Diagnostics go to stderr with {@link #OUTPUT_PREFIX} so a skip names its reason.
 */
public class ContainerPrerequisiteExtension implements ExecutionCondition {

    static final String REQUIRE_CONTAINER_PROPERTY = "orchard.e2e.require-container";
    static final String OUTPUT_PREFIX = "[container-prerequisites] ";
    static final String CLI_VERSION_KEY = "orchard.nursery.devcontainer-cli.version";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        List<String> missing = new ArrayList<>();
        LocalCommandRunner local = new LocalCommandRunner();
        String pinned = pinnedCliVersion();

        try {
            if (ContainerRuntimes.resolve(local).isEmpty()) {
                missing.add("No reachable container engine: both `docker info` and `podman info` failed");
            }

            String version = installedCliVersion(local);
            if (version == null) {
                missing.add("devcontainer CLI not found (install via: npm install -g @devcontainers/cli@"
                    + pinned + ")");
            } else if (!pinned.equals(version)) {
                missing.add("devcontainer CLI version " + version + " does not match the pinned "
                    + pinned + " (install via: npm install -g @devcontainers/cli@" + pinned + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while probing container prerequisites", e);
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

    private static String installedCliVersion(LocalCommandRunner local) throws InterruptedException {
        try {
            return local.execute("devcontainer --version").trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static String pinnedCliVersion() {
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:application.yml")) {
                YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
                yaml.setResources(resource);
                Properties properties = yaml.getObject();
                String version = properties == null ? null : properties.getProperty(CLI_VERSION_KEY);
                if (version != null && !version.isBlank()) {
                    return version.trim();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read application.yml from the classpath", e);
        }
        throw new IllegalStateException("application.yml on the classpath does not define " + CLI_VERSION_KEY);
    }
}
