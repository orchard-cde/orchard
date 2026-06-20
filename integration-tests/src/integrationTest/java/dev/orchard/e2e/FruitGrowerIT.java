package dev.orchard.e2e;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.FruitState;
import dev.orchard.core.model.Seed;
import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.nursery.SeedlingProvider;
import dev.orchard.nursery.SshExecutor;
import dev.orchard.trellis.OrchardApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof for issue #74: features actually apply via the new
 * {@code @devcontainers/cli} grow path, and {@link Fruit#containerName()} survives
 * the CLI transition (regression-critical — it's the join key to docker/SSH and a
 * sentinel value would mean the rest of the system can't observe the running
 * container).
 *
 * <p>This test deliberately mirrors {@link GroveLifecycleE2ETest}'s shape — Spring
 * Boot context against the {@code e2etest} profile, {@link QemuPrerequisiteExtension}
 * gates execution to environments that actually have QEMU / cloud-init / SSH keys.
 * Unlike that test, we bypass the HTTP API and drive the {@link SeedlingProvider}
 * and {@link FruitGrower} beans directly so the assertions land squarely on the
 * regression-critical surfaces (CLI invocation, containerName preservation,
 * feature application).
 */
@SpringBootTest(
    classes = OrchardApplication.class,
    properties = {
        "orchard.nursery.use-devcontainer-cli=true"
    }
)
@ActiveProfiles({"devserver", "e2etest"})
@ExtendWith(QemuPrerequisiteExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FruitGrowerIT {

    private static final String FEATURE_ID = "ghcr.io/devcontainers/features/common-utils:2";
    private static final String FEATURE_USERNAME = "orchard";

    @Autowired
    private ProviderRegistry providerRegistry;

    @Autowired
    private FruitGrower fruitGrower;

    @Autowired
    private DevcontainerCliConfig devcontainerCliConfig;

    private SeedlingProvider provider;
    private Seedling seedling;
    private Fruit grown;

    @BeforeAll
    void plantSeedling() {
        provider = providerRegistry.getDefault();
        assertThat(provider.getProviderId())
            .as("Default provider must be qemu-local for this IT")
            .isEqualTo("qemu-local");

        UUID groveId = UUID.randomUUID();
        Seedling germinated = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());

        seedling = provider.plant(germinated).join();

        assertThat(seedling.state())
            .as("Seedling must reach SAPLING — plant() returned %s", seedling.state())
            .isEqualTo(SeedlingState.SAPLING);
        assertThat(seedling.ipAddress()).isNotBlank();
        assertThat(seedling.sshPort()).isPositive();

        waitForCloudInit();
    }

    private void waitForCloudInit() {
        SshExecutor ssh = new SshExecutor(seedling);
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                String status = ssh.execute("cloud-init status 2>/dev/null || echo 'not available'").trim();
                if (status.contains("done") || status.contains("not available")) {
                    return;
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    @Order(1)
    void devcontainerCliIsInstalledAtExpectedVersion() throws Exception {
        String version = new SshExecutor(seedling).execute("devcontainer --version").trim();
        assertThat(version)
            .as("cloud-init must install the pinned @devcontainers/cli version")
            .isEqualTo(devcontainerCliConfig.version());
    }

    @Test
    @Order(2)
    void growsFruitViaCliAndPreservesContainerName() throws Exception {
        // Stage devcontainer.json with a real, public feature on the seedling's workspace.
        // The CLI reads /workspace/.devcontainer/devcontainer.json by default.
        SshExecutor ssh = new SshExecutor(seedling);
        ssh.execute("mkdir -p /workspace/.devcontainer");
        String devcontainerJson = """
            {
              "name": "orchard-fruit-it",
              "image": "mcr.microsoft.com/devcontainers/base:ubuntu",
              "features": {
                "%s": { "username": "%s" }
              }
            }
            """.formatted(FEATURE_ID, FEATURE_USERNAME);
        // base64-encode to avoid heredoc / quoting surprises through ssh argv.
        String b64 = java.util.Base64.getEncoder().encodeToString(devcontainerJson.getBytes());
        ssh.execute("echo " + b64 + " | base64 -d > /workspace/.devcontainer/devcontainer.json");

        // Build the Seed/Fruit mirroring how SeedParser would shape them. We only need image +
        // features here — the CLI path reads the on-disk devcontainer.json for everything else
        // (Locked decision #1).
        Map<String, Map<String, Object>> features = new LinkedHashMap<>();
        features.put(FEATURE_ID, Map.of("username", FEATURE_USERNAME));

        Seed seed = Seed.builder()
            .name("orchard-fruit-it")
            .image("mcr.microsoft.com/devcontainers/base:ubuntu")
            .features(features)
            .build();

        Fruit budding = Fruit.bud(seedling.groveId(), seedling.id(), seed);
        String originallyAssignedName = budding.containerName();
        assertThat(originallyAssignedName).isNotBlank();

        grown = fruitGrower.grow(seedling, budding).join();

        assertThat(grown.state())
            .as("Fruit must reach RIPE — grow() returned %s", grown.state())
            .isEqualTo(FruitState.RIPE);

        // Regression-critical: containerName must survive the CLI transition.
        // Pre-#74 the CLI path silently lost this field, breaking every downstream consumer
        // (grove status, SSH config rendering, docker exec join).
        assertThat(grown.containerName())
            .as("Fruit.containerName must be non-null and non-blank after CLI grow")
            .isNotNull()
            .isNotBlank();
        assertThat(grown.containerId())
            .as("Fruit.containerId must be set by the CLI outcome line")
            .isNotBlank();

        // The container the CLI actually started must match the recorded name.
        String running = ssh.execute(
            "docker inspect " + grown.containerName()
                + " --format '{{.State.Running}}'").trim();
        assertThat(running)
            .as("Container named %s must be running on the seedling", grown.containerName())
            .isEqualTo("true");
    }

    @Test
    @Order(3)
    void featureWasApplied_createsOrchardUserInsideContainer() throws Exception {
        assertThat(grown).as("grown must be set by the previous test").isNotNull();

        // common-utils with username=orchard creates a real local user inside the container.
        // `id orchard` proves the feature actually ran — the legacy docker path would silently
        // skip features and this assertion would fail with "no such user".
        String idOutput = new SshExecutor(seedling)
            .execute("docker exec " + grown.containerId() + " id " + FEATURE_USERNAME)
            .trim();

        assertThat(idOutput)
            .as("common-utils feature must have created the '%s' user inside the container",
                FEATURE_USERNAME)
            .contains("uid=")
            .contains(FEATURE_USERNAME);
    }

    @Test
    @Order(4)
    void picksAndCompostsCleanly() throws Exception {
        assertThat(grown).as("grown must be set by the previous test").isNotNull();

        Fruit picked = fruitGrower.pick(seedling, grown).join();
        assertThat(picked.state()).isEqualTo(FruitState.PICKED);

        fruitGrower.compost(seedling, grown).join();

        // After compost, docker inspect should fail (non-zero exit) because the container is gone.
        // SshExecutor.execute() raises IOException on non-zero exit — that's the success signal here.
        SshExecutor ssh = new SshExecutor(seedling);
        boolean inspectFailed;
        try {
            ssh.execute("docker inspect " + grown.containerId() + " > /dev/null 2>&1");
            inspectFailed = false;
        } catch (Exception expected) {
            inspectFailed = true;
        }
        assertThat(inspectFailed)
            .as("docker inspect must fail after compost — container should be removed")
            .isTrue();
    }

    @AfterAll
    void uprootSeedling() {
        // Layer 1: best-effort container teardown if a test failed mid-way and grown is still set.
        if (grown != null && grown.containerId() != null) {
            try {
                fruitGrower.compost(seedling, grown).join();
            } catch (Exception ignored) {
                // Fall through to VM teardown.
            }
        }

        // Layer 2: uproot the VM via the provider so QEMU process + disk image are cleaned up.
        if (seedling != null && provider != null) {
            try {
                provider.uproot(seedling).join();
            } catch (Exception ignored) {
                // Fall through to process cleanup.
            }
        }

        // Layer 3: kill any orphaned QEMU processes spawned by this test. Matches
        // GroveLifecycleE2ETest's belt-and-braces pattern — Conductor-style worktrees can leak
        // QEMU processes across test runs if uproot() didn't get a chance to land.
        try {
            new ProcessBuilder("pkill", "-f", "qemu-system.*orchard-e2e")
                .redirectErrorStream(true)
                .start()
                .waitFor();
        } catch (Exception ignored) {
            // Best effort.
        }
    }
}
