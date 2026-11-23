package dev.orchard.e2e;

import dev.orchard.trowel.Trowel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TrowelConfigIntegrationTest {

    @TempDir
    Path home;

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private String originalHome;

    @BeforeEach
    void setUp() {
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setProperty("user.home", originalHome);
    }

    private int execute(String... args) {
        return Trowel.createCommandLine().execute(args);
    }

    private void given(String... args) {
        assertThat(execute(args)).as("setup: trowel %s", String.join(" ", args)).isZero();
        outContent.reset();
        errContent.reset();
    }

    @Test
    void configShow_noFile_showsDefault() {
        int exitCode = execute("config", "show");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("no configuration file found");
    }

    @Test
    void configInit_createsTomlFile() {
        int exitCode = execute("config", "init");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Created configuration");
        assertThat(home.resolve(".orchard/config.toml")).exists();
        assertThat(home.resolve(".orchard/config.properties")).doesNotExist();
    }

    @Test
    void configShow_afterInit_showsTomlTargets() {
        given("config", "init");

        int exitCode = execute("config", "show");

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains("Active target:");
        assertThat(output).contains("server");
        assertThat(output).contains("cultivator");
    }

    @Test
    void configSet_updatesActiveTarget() {
        given("config", "init");

        int exitCode = execute("config", "set", "--server", "http://custom:9090");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Set server");
    }

    @Test
    void configShow_afterSet_showsUpdatedServer() {
        given("config", "init");
        given("config", "set", "--server", "http://custom:9090");

        int exitCode = execute("config", "show");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("http://custom:9090");
    }

    @Test
    void configTargetList_showsActiveTarget() {
        given("config", "init");

        int exitCode = execute("config", "target", "list");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("* ");
    }

    @Test
    void configTargetAdd_addsNewTarget() {
        given("config", "init");

        int exitCode = execute("config", "target", "add", "staging",
                "--server", "https://staging.example.com",
                "--cultivator", "staging-uuid");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Added target 'staging'");
    }

    @Test
    void configTargetList_showsBothTargets() {
        given("config", "init");
        givenStagingTarget();

        int exitCode = execute("config", "target", "list");

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains("staging");
        assertThat(output).contains("https://staging.example.com");
    }

    @Test
    void configTargetSet_switchesActiveTarget() {
        given("config", "init");
        givenStagingTarget();

        int exitCode = execute("config", "target", "set", "staging");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Active target set to 'staging'");
    }

    @Test
    void targetFlag_overridesActiveTarget() {
        given("config", "init");
        given("config", "set", "--server", "http://custom:9090");
        givenStagingTarget();
        given("config", "target", "set", "staging");

        int exitCode = execute("--target", "local", "config", "show");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("http://custom:9090");
    }

    @Test
    void configTargetRemove_activeTarget_fails() {
        given("config", "init");
        givenStagingTarget();
        given("config", "target", "set", "staging");

        int exitCode = execute("config", "target", "remove", "staging");

        assertThat(exitCode).isEqualTo(1);
        assertThat(errContent.toString()).contains("Cannot remove the active target");
    }

    @Test
    void configTargetRemove_nonActiveTarget_removes() {
        given("config", "init");
        givenStagingTarget();

        int exitCode = execute("config", "target", "remove", "staging");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Removed target 'staging'");
    }

    @Test
    void legacyPropertiesFile_readsAsDefaultTarget() throws Exception {
        Files.createDirectories(home.resolve(".orchard"));
        Files.writeString(home.resolve(".orchard/config.properties"),
                "server=http://legacy:8888\ncultivator=legacy-uuid\n");

        int exitCode = execute("config", "show");

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains("legacy format");
        assertThat(output).contains("http://legacy:8888");
    }

    @Test
    void devServer_showsUsage() {
        int exitCode = execute("dev-server");

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains("start");
        assertThat(output).contains("stop");
        assertThat(output).contains("status");
    }

    @Test
    void devServerStatus_showsStopped() {
        int exitCode = execute("dev-server", "status");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("stopped");
    }

    @Test
    void devServerStop_handlesNotRunning() {
        int exitCode = execute("dev-server", "stop");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("not running");
    }

    @Test
    void devServerStart_failsNoBinary() {
        int exitCode = execute("dev-server", "start");

        assertThat(exitCode).isEqualTo(1);
        assertThat(errContent.toString()).contains("binary not found");
    }

    private void givenStagingTarget() {
        given("config", "target", "add", "staging",
                "--server", "https://staging.example.com",
                "--cultivator", "staging-uuid");
    }
}
