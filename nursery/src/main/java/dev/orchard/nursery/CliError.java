package dev.orchard.nursery;

/**
 * Failure result of {@code devcontainer up} (outcome=error line in the CLI JSON stream).
 * {@code disallowedFeatureId} is set when the image rejected a feature.
 */
public record CliError(
    String message,
    String description,
    String disallowedFeatureId,
    String containerId,
    Boolean didStopContainer,
    String learnMoreUrl
) {}
