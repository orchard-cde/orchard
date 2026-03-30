package dev.orchard.e2e;

import dev.orchard.trellis.OrchardApplication;
import dev.orchard.trowel.Trowel;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static dev.orchard.e2e.E2ETestConstants.TEST_CULTIVATOR_ID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = OrchardApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles({"devserver", "e2etest"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrowelCommandIntegrationTest {

    @LocalServerPort
    private int port;

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

    private UUID groveId;

    @BeforeEach
    void setUp() {
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private int execute(String... args) {
        String[] fullArgs = Stream.concat(
            Stream.of("-s", "http://localhost:" + port, "--cultivator", TEST_CULTIVATOR_ID.toString()),
            Arrays.stream(args)
        ).toArray(String[]::new);
        return new CommandLine(new Trowel()).execute(fullArgs);
    }

    @Test
    @Order(1)
    void rootCommand_showsHelp() {
        int exitCode = execute();

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Cultivate your cloud development environments");
    }

    @Test
    @Order(2)
    void statusCommand_showsHealthyServer() {
        int exitCode = execute("status");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("healthy");
    }

    @Test
    @Order(3)
    void groveList_emptyInitially() {
        int exitCode = execute("grove", "list");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("No groves found");
    }

    @Test
    @Order(4)
    void grovePlant_createsGrove() {
        int exitCode = execute("grove", "plant", "https://github.com/devcontainers/template-starter");

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains("Planting grove");

        Matcher matcher = Pattern.compile("ID:\\s+([0-9a-f-]{36})").matcher(output);
        assertThat(matcher.find()).as("Expected UUID in output matching 'ID: <uuid>'").isTrue();
        groveId = UUID.fromString(matcher.group(1));
    }

    @Test
    @Order(5)
    void groveList_showsPlantedGrove() {
        assertThat(groveId).as("groveId must be set by grovePlant_createsGrove").isNotNull();

        int exitCode = execute("grove", "list");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains(groveId.toString());
    }

    @Test
    @Order(6)
    void groveShow_displaysGroveDetails() {
        assertThat(groveId).as("groveId must be set by grovePlant_createsGrove").isNotNull();

        int exitCode = execute("grove", "show", groveId.toString());

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains(groveId.toString());
        assertThat(output).contains("Repository:");
        assertThat(output).contains("Branch:");
    }

    @Test
    @Order(7)
    void groveClear_deletesGrove() {
        assertThat(groveId).as("groveId must be set by grovePlant_createsGrove").isNotNull();

        int exitCode = execute("grove", "clear", "-f", groveId.toString());

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("has been cleared");
    }
}
