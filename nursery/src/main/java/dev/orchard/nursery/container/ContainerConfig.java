package dev.orchard.nursery.container;

import java.nio.file.Path;

/** Configuration for the local container provider: the host directory under which each plot's workspace lives. */
public record ContainerConfig(Path workspaceRoot) {}
