package dev.orchard.e2e;

import dev.orchard.api.dto.GroveResponse;
import dev.orchard.core.model.GroveState;
import dev.orchard.trellis.OrchardApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static dev.orchard.e2e.GroveClient.runOnSeedling;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(QemuPrerequisiteExtension.class)
class GroveSurvivesRestartE2ETest {

    private static final Logger log = LoggerFactory.getLogger(GroveSurvivesRestartE2ETest.class);

    private final String datasourceUrl =
        "jdbc:h2:mem:orchard-e2e-restart-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private ConfigurableApplicationContext context;
    private GroveClient groves;

    @BeforeEach
    void startServer() {
        startTrellis();
    }

    @AfterEach
    void stopServerAndStrayVms() {
        if (context != null && context.isActive()) {
            context.close();
        }
        GroveClient.killStrayVmsAndScratch();
    }

    @Test
    void flourishingGroveSurvivesServerRestartAndUprootsCleanly() throws Exception {
        UUID groveId = groves.plant();
        try {
            GroveResponse beforeRestart = groves.awaitFlourishing(groveId, "before restart");
            assertSshWorks(beforeRestart, "before restart");

            restartTrellis();

            GroveResponse afterRestart = groves.awaitFlourishing(groveId, "after restart");
            assertSshWorks(afterRestart, "after restart");

            groves.clearAndAwaitCleared(groveId);
            assertNoQemuProcess(afterRestart.plot().id());
        } finally {
            groves.clearQuietly(groveId);
        }
    }

    @Test
    void stoppedGroveSurvivesServerRestartAndResumes() throws Exception {
        UUID groveId = groves.plant();
        try {
            GroveResponse flourishing = groves.awaitFlourishing(groveId, "before stop");
            runOnSeedling(flourishing.plot().sshPort(), "echo kept > /workspace/.orchard-e2e-marker");
            groves.stopAndAwaitDormant(groveId);

            restartTrellis();

            groves.assertStopped(groveId, GroveState.DORMANT, "after restart");
            groves.start(groveId);
            GroveResponse resumed = groves.awaitFlourishing(groveId, "after start");
            assertThat(runOnSeedling(resumed.plot().sshPort(), "cat /workspace/.orchard-e2e-marker"))
                .as("after start: workspace file survived stop, restart and start").contains("kept");
        } finally {
            groves.clearQuietly(groveId);
        }
    }

    @Test
    void groveWhosePlotWasPoweredOffWhileServerWasDownResumes() throws Exception {
        UUID groveId = groves.plant();
        try {
            GroveResponse flourishing = groves.awaitFlourishing(groveId, "before power-off");

            stopTrellis();
            killQemuProcess(flourishing.plot().id());
            startTrellis();

            groves.assertStopped(groveId, GroveState.BLIGHTED, "after restart");
            groves.start(groveId);
            GroveResponse resumed = groves.awaitFlourishing(groveId, "after start");
            assertSshWorks(resumed, "after start");
        } finally {
            groves.clearQuietly(groveId);
        }
    }

    private void startTrellis() {
        context = SpringApplication.run(OrchardApplication.class,
            "--spring.profiles.active=devserver,e2etest",
            "--spring.datasource.url=" + datasourceUrl,
            "--server.port=0");
        String baseUrl = "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
        groves = new GroveClient(baseUrl);
        log.info("Server started at {}", baseUrl);
    }

    private void stopTrellis() {
        log.info("Shutting down server...");
        context.close();
    }

    private void restartTrellis() {
        stopTrellis();
        startTrellis();
    }

    private static void assertSshWorks(GroveResponse grove, String step) throws Exception {
        assertThat(runOnSeedling(grove.plot().sshPort(), "echo orchard-e2e-restart-ok"))
            .as("%s: ssh output", step).contains("orchard-e2e-restart-ok");
    }

    private static List<ProcessHandle> qemuProcessesFor(UUID plotId) {
        return ProcessHandle.allProcesses()
            .filter(p -> p.info().commandLine()
                .map(cmd -> cmd.contains("qemu-system") && cmd.contains(plotId.toString()))
                .orElse(false))
            .toList();
    }

    private static void killQemuProcess(UUID plotId) throws Exception {
        List<ProcessHandle> vms = qemuProcessesFor(plotId);
        assertThat(vms).as("power-off: exactly one QEMU process for plot %s", plotId).hasSize(1);
        ProcessHandle vm = vms.getFirst();
        log.info("Killing QEMU process {} for plot {}", vm.pid(), plotId);
        vm.destroyForcibly();
        vm.onExit().get(30, TimeUnit.SECONDS);
        assertThat(vm.isAlive()).as("power-off: QEMU process %s exited", vm.pid()).isFalse();
    }

    private static void assertNoQemuProcess(UUID plotId) {
        assertThat(qemuProcessesFor(plotId))
            .as("uproot: no orphaned QEMU process should remain for plot %s", plotId)
            .isEmpty();
    }
}
