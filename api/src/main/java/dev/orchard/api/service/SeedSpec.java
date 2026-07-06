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

    /** Prefer {@code devcontainer.json}, else {@code devfile.yaml}, else a default seed. */
    AUTO,

    /** Use {@code devcontainer.json} only, ignoring any {@code devfile.yaml}. */
    DEVCONTAINER,

    /** Use {@code devfile.yaml} only, ignoring any {@code devcontainer.json}. */
    DEVFILE;

    /**
     * Parses a {@code --spec} flag value case-insensitively; {@code null} or blank maps to
     * {@link #AUTO}. Throws {@link IllegalArgumentException} on an unrecognized value.
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
