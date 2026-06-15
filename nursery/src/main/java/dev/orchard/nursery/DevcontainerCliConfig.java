package dev.orchard.nursery;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link DevcontainerCli} invocations.
 *
 * <p>Pinning the CLI version (default {@code 0.87.0}) prevents drift between Seedlings —
 * cloud-init installs this exact npm version on every VM, so {@code devcontainer up}
 * behaviour is reproducible across the fleet.
 *
 * <p>Timeouts default to 600s for {@code up} (covers feature-heavy builds incl. JDK / IDE
 * download) and 60s for {@code exec} (per-step lifecycle commands).
 */
@ConfigurationProperties("orchard.nursery.devcontainer-cli")
public record DevcontainerCliConfig(
    /** Pinned @devcontainers/cli version installed on every Seedling. */
    String version,
    /** Default timeout for {@code devcontainer up} invocations. */
    long upTimeoutSeconds,
    /** Default timeout for {@code devcontainer exec} invocations. */
    long execTimeoutSeconds
) {
    public DevcontainerCliConfig {
        if (version == null || version.isBlank()) version = "0.87.0";
        if (upTimeoutSeconds <= 0) upTimeoutSeconds = 600;
        if (execTimeoutSeconds <= 0) execTimeoutSeconds = 60;
    }
}
