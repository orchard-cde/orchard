package dev.orchard.nursery.container;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Configuration for the local container provider: the host directory under which each plot's workspace
 * lives (default {@code ${user.home}/.orchard/plots}), and the engine endpoint ({@code unix://} or
 * {@code tcp://}), null to use the environment's engine.
 */
@ConfigurationProperties("orchard.nursery.container")
public record ContainerConfig(Path workspaceRoot, String engineUrl) {
    public ContainerConfig {
        if (workspaceRoot == null) {
            workspaceRoot = Path.of(System.getProperty("user.home"), ".orchard", "plots");
        }
        if (engineUrl != null && engineUrl.isBlank()) {
            engineUrl = null;
        }
    }
}
