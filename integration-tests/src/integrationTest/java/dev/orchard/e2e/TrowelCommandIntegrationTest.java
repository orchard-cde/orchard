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
class TrowelCommandIntegrationTest {

    @LocalServerPort
    private int port;

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

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
    void rootCommand_showsHelp() {
        int exitCode = execute();

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Cultivate your cloud development environments");
    }

    @Test
    void statusCommand_showsHealthyServer() {
        int exitCode = execute("status");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("healthy");
    }

    @Test
    void grovePlantListShowClear() {
        assertThat(execute("grove", "list")).as("list before plant: exit code").isZero();
        assertThat(outContent.toString()).as("list before plant").contains("No groves found");
        outContent.reset();

        UUID groveId = plantGrove();
        boolean cleared = false;
        try {
            assertThat(execute("grove", "list")).as("list: exit code").isZero();
            assertThat(outContent.toString()).as("list: output").contains(groveId.toString());
            outContent.reset();

            assertThat(execute("grove", "show", groveId.toString())).as("show: exit code").isZero();
            assertThat(outContent.toString()).as("show: output")
                .contains(groveId.toString())
                .contains("Repository:")
                .contains("Branch:");
            outContent.reset();

            assertThat(execute("grove", "clear", "-f", groveId.toString())).as("clear: exit code").isZero();
            cleared = true;
            assertThat(outContent.toString()).as("clear: output").contains("has been cleared");
        } finally {
            if (!cleared) {
                execute("grove", "clear", "-f", groveId.toString());
            }
        }
    }

    private UUID plantGrove() {
        int exitCode = execute("grove", "plant", "https://github.com/devcontainers/template-starter");

        assertThat(exitCode).as("plant: exit code").isZero();
        String output = outContent.toString();
        assertThat(output).as("plant: output").contains("Planting grove");

        Matcher matcher = Pattern.compile("ID:\\s+([0-9a-f-]{36})").matcher(output);
        assertThat(matcher.find()).as("plant: expected UUID in output matching 'ID: <uuid>'").isTrue();
        outContent.reset();
        return UUID.fromString(matcher.group(1));
    }
}
