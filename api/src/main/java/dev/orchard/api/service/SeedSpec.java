package dev.orchard.api.service;

/**
 * Selects which workspace-config format Orchard uses when discovering a
 * {@link dev.orchard.core.model.Seed} from a freshly cloned repo, and therefore the
 * precedence rule applied when a repo ships <em>both</em> a {@code devcontainer.json}
 * and a {@code devfile.yaml}.
 *
 * <p>Maps the {@code trowel plant --spec} CLI flag. The default ({@link #AUTO}) preserves
 * Orchard's historical behavior of preferring {@code devcontainer.json}.
 */
public enum SeedSpec {

    /**
     * Default. Prefer {@code devcontainer.json}; fall back to {@code devfile.yaml} when
     * no devcontainer is present; fall back to a synthesized default devcontainer seed
     * when neither exists.
     */
    AUTO,

    /**
     * Use {@code devcontainer.json} only. Any {@code devfile.yaml} in the repo is ignored.
     * When no {@code devcontainer.json} is present, a default devcontainer seed is synthesized.
     */
    DEVCONTAINER,

    /**
     * Use {@code devfile.yaml} only. Any {@code devcontainer.json} in the repo is ignored.
     * When no {@code devfile.yaml} is present, a default devfile seed is synthesized.
     */
    DEVFILE;

    /**
     * Parses a {@code --spec} flag value case-insensitively. A {@code null} or blank value
     * (the flag was omitted) maps to {@link #AUTO}.
     *
     * @throws IllegalArgumentException if the value is non-blank but unrecognized.
     */
    public static SeedSpec fromFlag(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return switch (value.trim().toLowerCase()) {
            case "auto" -> AUTO;
            case "devcontainer" -> DEVCONTAINER;
            case "devfile" -> DEVFILE;
            default -> throw new IllegalArgumentException(
                "Unknown --spec value '" + value + "'. Valid values: auto, devcontainer, devfile.");
        };
    }
}
