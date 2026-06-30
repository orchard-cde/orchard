package dev.orchard.e2e;

import java.time.Duration;
import java.util.UUID;

public final class E2ETestConstants {

    private E2ETestConstants() {}

    public static final String TEST_REPO_URL = "https://github.com/devcontainers/template-starter";
    public static final String TEST_REPO_BRANCH = "main";

    public static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    // Must stay strictly greater than orchard.nursery.devcontainer-cli.up-timeout-seconds
    // (600s / 10min). A hung `devcontainer up` is converted into a clean BLIGHT -> uproot
    // teardown when the inner up-timeout fires; this outer poll budget must outlast that so
    // the teardown wins the race and the VM is reaped instead of leaked (issue #138).
    public static final Duration GROVE_FLOURISHING_TIMEOUT = Duration.ofMinutes(15);
    public static final Duration GROVE_CLEARED_TIMEOUT = Duration.ofMinutes(2);

    public static final UUID TEST_CULTIVATOR_ID = UUID.fromString("e2e00000-0000-0000-0000-000000000001");
}
