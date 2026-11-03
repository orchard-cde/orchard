package dev.orchard.e2e;

import dev.orchard.api.dto.BeeResponse;
import dev.orchard.api.dto.CreateBeeRequest;
import dev.orchard.api.dto.GroveResponse;
import dev.orchard.core.model.BeeState;
import dev.orchard.core.model.BeeType;
import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.PlotState;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.trellis.OrchardApplication;
import dev.orchard.vine.ContainerLabels;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static dev.orchard.e2e.E2ETestConstants.GROVE_DORMANT_TIMEOUT;
import static dev.orchard.e2e.E2ETestConstants.POLL_INTERVAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
    classes = OrchardApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"devserver", "e2etest"})
@ExtendWith(ContainerPrerequisiteExtension.class)
class ContainerGroveE2ETest {

    private static final Path WORKSPACE_ROOT = newWorkspaceRoot();

    @DynamicPropertySource
    static void containerProvider(DynamicPropertyRegistry registry) {
        registry.add("orchard.nursery.provider", () -> "container");
        registry.add("orchard.nursery.container.workspace-root", WORKSPACE_ROOT::toString);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProviderRegistry providers;

    @LocalServerPort
    private int port;

    private GroveClient groves;

    @AfterAll
    static void deleteWorkspaceRoot() throws IOException {
        FileSystemUtils.deleteRecursively(WORKSPACE_ROOT);
    }

    @BeforeEach
    void connect() {
        groves = new GroveClient("http://localhost:" + port);
    }

    @Test
    void containerGroveFlourishesRunsBeeStopsStartsAndClears() throws Exception {
        UUID groveId = groves.plant();
        try {
            GroveResponse flourishing = groves.awaitRooted(groveId, "flourishing");
            assertThat(flourishing.plot().providerId()).as("flourishing: provider").isEqualTo("container");
            assertThat(flourishing.fruits()).as("flourishing: fruits").isNotEmpty();
            ContainerPlot plot = containerPlot(flourishing, PlotState.ROOTED);
            GroveProvider provider = providers.require(plot.providerId());

            assertThat(provider.vine(plot).commands().execute("cat /etc/os-release"))
                .as("vine: os-release").contains("NAME=");

            smokeBee(groveId, provider, plot);

            groves.stopAndAwaitDormant(groveId);
            ContainerPlot dormant = containerPlot(groves.get(groveId), PlotState.DORMANT);
            assertThat(provider.inspect(dormant).get().state()).as("stop: inspect").isEqualTo(PlotState.DORMANT);

            groves.start(groveId);
            GroveResponse resumed = groves.awaitRooted(groveId, "start");
            ContainerPlot resumedPlot = containerPlot(resumed, PlotState.ROOTED);
            assertThat(provider.vine(resumedPlot).commands().execute("cat /etc/os-release"))
                .as("start: vine os-release").contains("NAME=");

            groves.clearAndAwaitCleared(groveId);
            assertThat(plot.workspacePath().getParent()).as("clear: plot directory removed").doesNotExist();
            assertThat(containersLabelled(plot)).as("clear: no containers left for plot %s", plot.id()).isEmpty();
        } finally {
            groves.clearQuietly(groveId);
        }
    }

    private void smokeBee(UUID groveId, GroveProvider provider, ContainerPlot plot) throws Exception {
        assumeThat(hasCurl(provider, plot)).as("fruit has curl, which bee provisioning needs").isTrue();

        ResponseEntity<BeeResponse> hatched = restTemplate.postForEntity(
            "/api/groves/" + groveId + "/bees", new CreateBeeRequest(BeeType.OPENCODE, null, null),
            BeeResponse.class);
        assertThat(hatched.getStatusCode()).as("bee: hatch status").isEqualTo(HttpStatus.ACCEPTED);
        UUID beeId = hatched.getBody().id();

        await().atMost(GROVE_DORMANT_TIMEOUT).pollInterval(POLL_INTERVAL)
            .untilAsserted(() -> assertThat(beeState(groveId, beeId)).as("bee: hatched")
                .isIn(BeeState.HIBERNATING, BeeState.BUZZING));

        ResponseEntity<BeeResponse> smoked = restTemplate.postForEntity(
            "/api/groves/" + groveId + "/bees/" + beeId + "/actions/smoke", null, BeeResponse.class);
        assertThat(smoked.getStatusCode()).as("bee: smoke status").isEqualTo(HttpStatus.OK);
        assertThat(smoked.getBody().state()).as("bee: smoked").isEqualTo(BeeState.SMOKED);
    }

    private BeeState beeState(UUID groveId, UUID beeId) {
        return restTemplate.getForObject("/api/groves/" + groveId + "/bees/" + beeId, BeeResponse.class).state();
    }

    private static boolean hasCurl(GroveProvider provider, ContainerPlot plot) throws InterruptedException {
        try {
            provider.vine(plot).commands().execute("command -v curl");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static ContainerPlot containerPlot(GroveResponse grove, PlotState state) {
        var info = grove.plot();
        return new ContainerPlot(info.id(), grove.id(), info.providerId(), info.id().toString(), state,
            null, null, WORKSPACE_ROOT.resolve(info.id().toString()).resolve(info.id().toString()),
            info.runtime(), null, null);
    }

    private static String containersLabelled(ContainerPlot plot) throws Exception {
        Process process = new ProcessBuilder(plot.runtime().cli(), "ps", "-aq",
            "--filter", ContainerLabels.filter(plot.id()))
            .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes()).trim();
        assertThat(process.waitFor()).as("%s ps exit code (output: %s)", plot.runtime().cli(), out).isZero();
        return out;
    }

    private static Path newWorkspaceRoot() {
        try {
            return Files.createTempDirectory("orchard-e2e-container-plots");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
