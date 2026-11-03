package dev.orchard.nursery.container;

import java.nio.file.Path;

/**
 * Configuration for the local container provider: the host directory under which each plot's workspace
 * lives, and the engine endpoint ({@code unix://} or {@code tcp://}), null to use the environment's engine.
 */
public record ContainerConfig(Path workspaceRoot, String engineUrl) {}
