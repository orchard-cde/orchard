package dev.orchard.nursery;

import dev.orchard.core.model.*;
import dev.orchard.nursery.event.FruitProgressEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link FruitGrower}.
 *
 * <p>The CLI path is tested by mocking {@link DevcontainerCli} directly. The legacy docker path
 * is tested via the feature flag — its behaviour mirrors the pre-#74 grow flow and is exercised
 * end-to-end by {@code FruitGrowerIT}; we only assert here that the legacy code path is taken
 * when the flag is off (CLI mock is never invoked) and that the {@code parsePortOutput} helpers
 * still round-trip correctly.
 */
class FruitGrowerTest {

    private static final UUID GROVE_ID = UUID.randomUUID();
    private static final UUID SEEDLING_ID = UUID.randomUUID();

    // --- parsePortOutput (legacy helper still used on docker path) ---------------------------

    @Test
    void parsePortOutput_standardTcpMapping() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("8080/tcp -> 0.0.0.0:8080");

        assertThat(mappings).containsExactly(new Fruit.PortMapping(8080, 8080, "tcp"));
    }

    @Test
    void parsePortOutput_multipleMappings() {
        String output = "8080/tcp -> 0.0.0.0:8080\n3000/tcp -> 0.0.0.0:3000";

        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput(output);

        assertThat(mappings).hasSize(2);
        assertThat(mappings.get(0)).isEqualTo(new Fruit.PortMapping(8080, 8080, "tcp"));
        assertThat(mappings.get(1)).isEqualTo(new Fruit.PortMapping(3000, 3000, "tcp"));
    }

    @Test
    void parsePortOutput_skipsIpv6Lines() {
        String output = "8080/tcp -> 0.0.0.0:8080\n8080/tcp -> [::]:8080";

        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput(output);

        assertThat(mappings).hasSize(1);
        assertThat(mappings.getFirst()).isEqualTo(new Fruit.PortMapping(8080, 8080, "tcp"));
    }

    @Test
    void parsePortOutput_differentHostPort() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("8080/tcp -> 0.0.0.0:49152");

        assertThat(mappings).containsExactly(new Fruit.PortMapping(8080, 49152, "tcp"));
    }

    @Test
    void parsePortOutput_emptyOutput() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("");
        assertThat(mappings).isEmpty();
    }

    @Test
    void parsePortOutput_noArrowOutput() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("no ports");
        assertThat(mappings).isEmpty();
    }

    @Test
    void parsePortOutput_udpProtocol() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("53/udp -> 0.0.0.0:53");

        assertThat(mappings).containsExactly(new Fruit.PortMapping(53, 53, "udp"));
    }

    // --- CLI path ----------------------------------------------------------------------------

    @Test
    void growViaCli_happyPath_returnsRipeFruit() throws Exception {
        DevcontainerCli cli = mock(DevcontainerCli.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        Seedling seedling = seedling();
        Fruit fruit = buddedFruit(defaultSeed());

        when(cli.up(eq(seedling), eq(fruit.id()), eq(fruit.containerName()), any()))
            .thenReturn(new DevcontainerCliResult("c123", null, "vscode", "/workspace"));
        when(cli.inspectContainerName(seedling, "c123")).thenReturn("real-container-name");

        FruitGrower grower = new FruitGrower(cli, true, events);

        Fruit result = grower.grow(seedling, fruit).get();

        assertThat(result.state()).isEqualTo(FruitState.RIPE);
        assertThat(result.containerId()).isEqualTo("c123");
        assertThat(result.containerName()).isEqualTo("real-container-name");
    }

    @Test
    void growViaCli_cliError_returnsRottedFruit() throws Exception {
        DevcontainerCli cli = mock(DevcontainerCli.class);
        Seedling seedling = seedling();
        Fruit fruit = buddedFruit(defaultSeed());

        CliError error = new CliError("boom", "feature install failed", "ghcr.io/devcontainers/features/bad:1",
            null, false, "https://example/docs");
        doThrow(new DevcontainerCli.DevcontainerCliException(error))
            .when(cli).up(any(), any(), anyString(), any());

        FruitGrower grower = new FruitGrower(cli, true, null);

        Fruit result = grower.grow(seedling, fruit).get();

        assertThat(result.state()).isEqualTo(FruitState.ROTTED);
        // Inspect should not be called once the up() throws.
        verify(cli, never()).inspectContainerName(any(), anyString());
    }

    @Test
    void growViaCli_postAttachWaitFor_staysBuddedUntilAttach() throws Exception {
        DevcontainerCli cli = mock(DevcontainerCli.class);
        Seedling seedling = seedling();
        Seed seed = Seed.builder()
            .name("post-attach-seed")
            .image("mcr.microsoft.com/devcontainers/base:ubuntu")
            .waitFor(WaitFor.POST_ATTACH_COMMAND)
            .postAttachCommand(new LifecycleCommand.Sequential(List.of("echo", "hi")))
            .build();
        Fruit fruit = buddedFruit(seed);

        when(cli.up(eq(seedling), eq(fruit.id()), eq(fruit.containerName()), any()))
            .thenReturn(new DevcontainerCliResult("c-pa", null, "vscode", "/workspace"));
        when(cli.inspectContainerName(seedling, "c-pa")).thenReturn(fruit.containerName());

        FruitGrower grower = new FruitGrower(cli, true, null);

        // grow() must NOT flip to RIPE when waitFor=POST_ATTACH_COMMAND.
        Fruit afterGrow = grower.grow(seedling, fruit).get();
        assertThat(afterGrow.state()).isEqualTo(FruitState.BUDDING);
        assertThat(afterGrow.containerId()).isEqualTo("c-pa");

        // attach() runs the post-attach command via CLI exec and flips to RIPE.
        Fruit afterAttach = grower.attach(seedling, afterGrow).get();
        verify(cli).exec(eq(seedling), eq("echo hi"));
        assertThat(afterAttach.state()).isEqualTo(FruitState.RIPE);
    }

    @Test
    void growViaCli_publishesPhaseTransitionEvents() throws Exception {
        DevcontainerCli cli = mock(DevcontainerCli.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        Seedling seedling = seedling();
        Fruit fruit = buddedFruit(defaultSeed());

        // Simulate the CLI streaming three JSON lines: a build start, a feature install, and
        // the terminal outcome=success. The grower's PhaseTransitionFilter should publish on
        // each distinct phase boundary.
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = inv.getArgument(3);
            sink.accept("{\"type\":\"progress\",\"message\":\"Building image abc\"}");
            sink.accept("{\"type\":\"progress\",\"message\":\"Running install.sh for ghcr.io/.../node:1\"}");
            sink.accept("{\"outcome\":\"success\",\"containerId\":\"c-events\"}");
            return new DevcontainerCliResult("c-events", null, "vscode", "/workspace");
        }).when(cli).up(any(), any(), anyString(), any());

        when(cli.inspectContainerName(seedling, "c-events")).thenReturn(fruit.containerName());

        FruitGrower grower = new FruitGrower(cli, true, events);

        Fruit result = grower.grow(seedling, fruit).get();

        assertThat(result.state()).isEqualTo(FruitState.RIPE);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce()).publishEvent(captor.capture());
        List<FruitProgressEvent> published = captor.getAllValues().stream()
            .filter(FruitProgressEvent.class::isInstance)
            .map(FruitProgressEvent.class::cast)
            .toList();
        assertThat(published).isNotEmpty();
        assertThat(published).extracting(FruitProgressEvent::phase)
            .containsExactly("BUILD_START", "FEATURE_INSTALL_START", "READY");
        assertThat(published.getFirst().fruitId()).isEqualTo(fruit.id());
        assertThat(published.getFirst().groveId()).isEqualTo(fruit.groveId());
    }

    @Test
    void growViaDocker_featureFlagOff_usesLegacyPath() throws Exception {
        DevcontainerCli cli = mock(DevcontainerCli.class);
        Seedling seedling = seedling();
        Fruit fruit = buddedFruit(defaultSeed());

        // Feature flag off — even though a CLI mock is wired, grow() must route to the legacy
        // path. We can't run docker over SSH from a unit test, so the seedling's bogus IP makes
        // executeSsh() fail; the grower's catch-all maps that to ROTTED. The critical assertion
        // is that the CLI mock was never touched.
        FruitGrower grower = new FruitGrower(cli, false, null);

        Fruit result = grower.grow(seedling, fruit).get();

        verify(cli, never()).up(any(), any(), anyString(), any());
        verify(cli, never()).inspectContainerName(any(), anyString());
        // The legacy path's SSH call will fail (no real VM), so the fruit rots — that's the
        // signal we took the legacy branch.
        assertThat(result.state()).isEqualTo(FruitState.ROTTED);
    }

    @Test
    void containerNameRegression_isPreservedAfterGrow() throws Exception {
        // Regression test for spec Locked decision #18. The CLI may name the actual container
        // differently from Fruit.containerName (e.g. on regrow). FruitGrower MUST call
        // inspectContainerName and overwrite Fruit.containerName with the real value.
        DevcontainerCli cli = mock(DevcontainerCli.class);
        Seedling seedling = seedling();
        Fruit fruit = buddedFruit(defaultSeed());
        String originalContainerName = fruit.containerName();

        when(cli.up(eq(seedling), eq(fruit.id()), eq(fruit.containerName()), any()))
            .thenReturn(new DevcontainerCliResult("c-real", null, "vscode", "/workspace"));
        when(cli.inspectContainerName(seedling, "c-real")).thenReturn("real-container-name");

        FruitGrower grower = new FruitGrower(cli, true, null);

        Fruit result = grower.grow(seedling, fruit).get();

        assertThat(result.containerName())
            .as("Fruit.containerName must reflect the actual host name returned by docker inspect")
            .isEqualTo("real-container-name")
            .isNotEqualTo(originalContainerName);
        assertThat(result.containerId()).isEqualTo("c-real");
        verify(cli).inspectContainerName(seedling, "c-real");
    }

    // --- helpers -----------------------------------------------------------------------------

    private static Seed defaultSeed() {
        return Seed.builder()
            .name("orchard-fruit")
            .image("mcr.microsoft.com/devcontainers/base:ubuntu")
            .build();
    }

    private static Fruit buddedFruit(Seed seed) {
        return Fruit.bud(GROVE_ID, SEEDLING_ID, seed);
    }

    private static Seedling seedling() {
        // 127.0.0.255 / port 1 — guaranteed to refuse on any host the test runs on.
        return new Seedling(
            SEEDLING_ID, GROVE_ID, "test-instance",
            "127.0.0.255", 1,
            SeedlingState.SAPLING,
            Seedling.SeedlingSpec.small(),
            Instant.now(), Instant.now());
    }
}
