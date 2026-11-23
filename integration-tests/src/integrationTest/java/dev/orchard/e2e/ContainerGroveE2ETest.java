package dev.orchard.e2e;

import dev.orchard.api.dto.BeeResponse;
import dev.orchard.api.dto.CreateBeeRequest;
import dev.orchard.api.dto.GroveResponse;
import dev.orchard.api.service.GroveService;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static dev.orchard.e2e.E2ETestConstants.E2E_SECRET_NAME;
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
@ExtendWith({ContainerPrerequisiteExtension.class, OutputCaptureExtension.class})
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

    @Autowired
    private GroveService groveService;

    @Autowired
    private JdbcTemplate jdbc;

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
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void containerGroveFlourishesRunsBeeStopsStartsAndClears(CapturedOutput output) throws Exception {
        String secret = "e2e-" + UUID.randomUUID();
        try {
            groves.putSecret(E2E_SECRET_NAME, secret);
            UUID groveId = groves.plant();
            try {
                exerciseContainerGrove(groveId, secret, output);
            } finally {
                groves.clearQuietly(groveId);
            }
        } finally {
            groves.deleteSecretQuietly(E2E_SECRET_NAME);
        }
    }

    private void exerciseContainerGrove(UUID groveId, String secret, CapturedOutput output) throws Exception {
        GroveResponse flourishing;
        try (var probe = SecretLeakProbe.onHost(secret)) {
            flourishing = groves.awaitRooted(groveId, "flourishing");
            FruitShell.of(groveService, providers, groveId)
                .assertSecretDelivered(E2E_SECRET_NAME, secret, "flourishing");
        }
        SecretLeakProbe.assertRowsFree(jdbc, secret, "flourishing");
        SecretLeakProbe.assertApiFree(restTemplate, groveId, secret, "flourishing");
        assertThat(flourishing.plot().providerId()).as("flourishing: provider").isEqualTo("container");
        assertThat(flourishing.fruits()).as("flourishing: fruits").isNotEmpty();
        ContainerPlot plot = containerPlot(flourishing, PlotState.ROOTED);
        GroveProvider provider = providers.require(plot.providerId());

        assertThat(provider.vine(plot).commands().execute("cat /etc/os-release"))
            .as("vine: os-release").contains("NAME=");
        assertThat(provider.vine(plot).commands().execute("cd /workspaces/* && git rev-parse HEAD").trim())
            .as("vine: git works in the workspace as the remote user").matches("[0-9a-f]{40}");

        smokeBee(groveId, provider, plot, secret);

        groves.stopAndAwaitDormant(groveId);
        ContainerPlot dormant = containerPlot(groves.get(groveId), PlotState.DORMANT);
        assertThat(provider.inspect(dormant).get().state()).as("stop: inspect").isEqualTo(PlotState.DORMANT);

        GroveResponse resumed;
        try (var probe = SecretLeakProbe.onHost(secret)) {
            groves.start(groveId);
            resumed = groves.awaitRooted(groveId, "start");
            FruitShell.of(groveService, providers, groveId).assertSecretDelivered(E2E_SECRET_NAME, secret, "start");
        }
        ContainerPlot resumedPlot = containerPlot(resumed, PlotState.ROOTED);
        assertThat(provider.vine(resumedPlot).commands().execute("cat /etc/os-release"))
            .as("start: vine os-release").contains("NAME=");
        SecretLeakProbe.assertRowsFree(jdbc, secret, "start");
        SecretLeakProbe.assertApiFree(restTemplate, groveId, secret, "start");

        assertThat(groves.get(groveId).missingSecrets()).as("warning: none while stored").isEmpty();
        groves.deleteSecret(E2E_SECRET_NAME);
        assertThat(groves.get(groveId).missingSecrets().getFirst().name()).as("warning: missing after delete")
            .isEqualTo(E2E_SECRET_NAME);
        groves.stopAndAwaitDormant(groveId);
        groves.start(groveId);
        groves.awaitRooted(groveId, "unset");
        assertThat(FruitShell.of(groveService, providers, groveId).loginEnv(E2E_SECRET_NAME))
            .as("unset: fruit login shell sees no value").isEmpty();

        groves.clearAndAwaitCleared(groveId);
        assertThat(plot.workspacePath().getParent()).as("clear: plot directory removed").doesNotExist();
        assertThat(containersLabelled(plot)).as("clear: no containers left for plot %s", plot.id()).isEmpty();
        assertThat(output.getAll()).as("trellis log: injection logged, value absent")
            .contains("Injected secrets [" + E2E_SECRET_NAME + "]")
            .doesNotContain(secret);
    }

    private void smokeBee(UUID groveId, GroveProvider provider, ContainerPlot plot, String secret) throws Exception {
        assumeThat(hasCurl(provider, plot)).as("fruit has curl, which bee provisioning needs").isTrue();

        ResponseEntity<BeeResponse> hatched = restTemplate.postForEntity(
            "/api/groves/" + groveId + "/bees", new CreateBeeRequest(BeeType.OPENCODE, null, null),
            BeeResponse.class);
        assertThat(hatched.getStatusCode()).as("bee: hatch status").isEqualTo(HttpStatus.ACCEPTED);
        UUID beeId = hatched.getBody().id();

        await().atMost(GROVE_DORMANT_TIMEOUT).pollInterval(POLL_INTERVAL)
            .untilAsserted(() -> assertThat(beeState(groveId, beeId)).as("bee: hatched")
                .isIn(BeeState.HIBERNATING, BeeState.BUZZING));
        await().atMost(GROVE_DORMANT_TIMEOUT).pollInterval(POLL_INTERVAL)
            .untilAsserted(() -> assertThat(beeState(groveId, beeId)).as("bee: buzzing").isEqualTo(BeeState.BUZZING));
        String beeLog = provider.vine(plot).commands().execute("cat /tmp/opencode.log || true");
        assertThat(provider.vine(plot).commands().execute(
            "curl -sS -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:4096/").trim())
            .as("bee: opencode server answers inside the fruit; log: %s", beeLog).matches("\\d{3}");
        String pid = restTemplate.getForObject("/api/groves/" + groveId + "/bees/" + beeId, BeeResponse.class)
            .processId();
        assertThat(pid).as("bee: process id").matches("\\d+");
        assertThat(provider.vine(plot).commands().execute("cat /proc/" + pid + "/environ | tr '\\0' '\\n'").lines())
            .as("bee: environment carries the secret").contains(E2E_SECRET_NAME + "=" + secret);

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

    /** The persisted plot, so the vine runs as the fruit's remote user exactly as a real grove does. */
    private ContainerPlot containerPlot(GroveResponse grove, PlotState state) {
        ContainerPlot plot = (ContainerPlot) groveService.getGrove(grove.id()).orElseThrow().plot();
        assertThat(plot.state()).as("plot state").isEqualTo(state);
        assertThat(plot.remoteUser()).as("plot remote user").isNotBlank();
        return plot;
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
