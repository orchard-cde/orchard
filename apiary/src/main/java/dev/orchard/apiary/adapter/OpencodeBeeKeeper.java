package dev.orchard.apiary.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.orchard.apiary.BeeKeeper;
import dev.orchard.apiary.BeeProvisioningException;
import dev.orchard.core.model.Bee;
import dev.orchard.core.model.BeeHealth;
import dev.orchard.core.model.BeeSpec;
import dev.orchard.core.model.BeeType;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.ShellQuote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BeeKeeper implementation for OpenCode (https://github.com/anomalyco/opencode).
 * Installs the CLI on the VM plot if missing and writes a workspace-scoped opencode.jsonc
 * config. By default runs `opencode serve` in the background as OpenCode's headless HTTP
 * server; if the bee's spec sets configOverrides["mode"] = "interactive", release() skips
 * starting a process instead, so the cultivator can launch `opencode` themselves from a
 * terminal (VS Code Remote/SSH pane) — e.g. for an interactive TUI session rather than the
 * headless API server.
 */
public class OpencodeBeeKeeper implements BeeKeeper {

    private static final String INSTALL_SCRIPT_URL =
        "https://opencode.ai/install";
    private static final String INSTALL_SCRIPT_PATH = "/tmp/opencode-install.sh";
    private static final String LOG_PATH = "/tmp/opencode.log";
    private static final String INSTALL_DIR_BIN = "$HOME/.opencode/bin/opencode";
    private static final String RESOLVE_BINARY =
        "command -v opencode || { test -x \"" + INSTALL_DIR_BIN + "\" && echo \"" + INSTALL_DIR_BIN + "\"; }";
    private static final int STARTUP_WAIT_SECONDS = 15;
    private static final int LOG_TAIL_LINES = 20;
    private static final int SERVER_PORT = 4096;
    private static final String CONFIG_KEY_MODE = "mode";
    private static final String MODE_HEADLESS = "headless";
    private static final String MODE_INTERACTIVE = "interactive";

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public BeeType getBeeType() {
        return BeeType.OPENCODE;
    }

    @Override
    public CompletableFuture<Bee> install(Bee bee, BeeSpec spec, CommandRunner runner,
            String workspaceRoot) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureBinaryInstalled(runner);
                writeConfig(runner, bee, spec, workspaceRoot);
                return bee;
            } catch (IOException e) {
                throw new BeeProvisioningException("Failed to install OpenCode for bee " + bee.id(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BeeProvisioningException("Interrupted while installing OpenCode for bee " + bee.id(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Bee> release(Bee bee, CommandRunner runner, String workspaceRoot) {
        return CompletableFuture.supplyAsync(() -> {
            if (isInteractiveMode(bee)) {
                return bee;
            }
            try {
                String binary = resolveBinary(runner);
                String pid = runner.execute(
                    "cd " + ShellQuote.quote(workspaceRoot) + " && nohup " + ShellQuote.quote(binary)
                        + " serve --port " + SERVER_PORT + " --hostname 127.0.0.1 > " + LOG_PATH
                        + " 2>&1 & echo $!").trim();
                awaitServer(runner, bee, pid);
                return bee.withProcessId(pid);
            } catch (IOException e) {
                throw new BeeProvisioningException("Failed to start OpenCode for bee " + bee.id(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BeeProvisioningException("Interrupted while starting OpenCode for bee " + bee.id(), e);
            }
        }, executor);
    }

    private boolean isInteractiveMode(Bee bee) {
        String mode = bee.spec().configOverrides().getOrDefault(CONFIG_KEY_MODE, MODE_HEADLESS);
        return MODE_INTERACTIVE.equalsIgnoreCase(mode);
    }

    @Override
    public CompletableFuture<Bee> smoke(Bee bee, CommandRunner runner) {
        return CompletableFuture.supplyAsync(() -> {
            if (bee.processId() != null) {
                try {
                    runner.execute("kill " + bee.processId());
                } catch (IOException | InterruptedException e) {
                    // Best-effort kill — process may already be gone.
                }
            }
            return bee;
        }, executor);
    }

    @Override
    public CompletableFuture<BeeHealth> inspect(Bee bee, CommandRunner runner) {
        return CompletableFuture.supplyAsync(() -> {
            boolean alive = isProcessAlive(bee, runner);
            boolean responsive = alive && isResponsive(runner);
            return new BeeHealth(alive, responsive, null, Instant.now());
        }, executor);
    }

    @Override
    public Map<String, String> prerequisites() {
        return Map.of(
            "curl", "required to fetch the OpenCode install script",
            "git", "required by OpenCode for repository-aware features"
        );
    }

    private void ensureBinaryInstalled(CommandRunner runner) throws IOException, InterruptedException {
        try {
            resolveBinary(runner);
        } catch (IOException e) {
            runner.execute("curl -fsSL " + INSTALL_SCRIPT_URL + " -o " + INSTALL_SCRIPT_PATH
                + " && bash " + INSTALL_SCRIPT_PATH + " --no-modify-path");
            resolveBinary(runner);
        }
    }

    private String resolveBinary(CommandRunner runner) throws IOException, InterruptedException {
        String path = runner.execute(RESOLVE_BINARY).trim();
        if (path.isEmpty()) {
            throw new IOException("opencode binary not found");
        }
        return path;
    }

    private void awaitServer(CommandRunner runner, Bee bee, String pid)
            throws IOException, InterruptedException {
        try {
            runner.execute(
                "for i in $(seq " + STARTUP_WAIT_SECONDS + "); do"
                    + " curl -sS -o /dev/null --max-time 2 http://127.0.0.1:" + SERVER_PORT + "/ && exit 0;"
                    + " kill -0 " + pid + " || exit 1; sleep 1; done; exit 1",
                STARTUP_WAIT_SECONDS * 3L);
        } catch (IOException e) {
            throw new BeeProvisioningException("OpenCode server for bee " + bee.id()
                + " did not start; " + LOG_PATH + ":\n" + logTail(runner), e);
        }
    }

    private String logTail(CommandRunner runner) throws InterruptedException {
        try {
            return runner.execute("tail -n " + LOG_TAIL_LINES + " " + LOG_PATH).trim();
        } catch (IOException e) {
            return "(log unavailable)";
        }
    }

    private void writeConfig(CommandRunner runner, Bee bee, BeeSpec spec, String workspaceRoot)
            throws IOException, InterruptedException {
        String config = renderConfig(bee, spec, workspaceRoot);
        String encoded = Base64.getEncoder().encodeToString(config.getBytes(StandardCharsets.UTF_8));
        String configDir = ShellQuote.quote(workspaceRoot + "/.opencode");
        String configPath = ShellQuote.quote(workspaceRoot + "/.opencode/opencode.jsonc");
        runner.execute("mkdir -p " + configDir + " && echo " + encoded + " | base64 -d > " + configPath);
    }

    // Package-private, not private: BeeKeeperAdapterBenchmark measures this in isolation.
    // Virtual-thread dispatch dominates the whole-method install() score, so a direct call is
    // the only way to get a signal that tracks changes to the config path.
    String renderConfig(Bee bee, BeeSpec spec, String workspaceRoot) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("workspace_root", workspaceRoot);

        ObjectNode orchard = root.putObject("orchard");
        orchard.put("grove_id", bee.groveId().toString());
        orchard.put("bee_id", bee.id().toString());

        for (Map.Entry<String, String> entry : spec.configOverrides().entrySet()) {
            root.put(entry.getKey(), entry.getValue());
        }

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private boolean isProcessAlive(Bee bee, CommandRunner runner) {
        if (bee.processId() == null) {
            return false;
        }
        try {
            runner.execute("kill -0 " + bee.processId());
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private boolean isResponsive(CommandRunner runner) {
        try {
            runner.execute(ShellQuote.quote(resolveBinary(runner)) + " --version", 5);
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
