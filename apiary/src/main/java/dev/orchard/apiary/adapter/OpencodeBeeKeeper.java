package dev.orchard.apiary.adapter;

import dev.orchard.apiary.BeeKeeper;
import dev.orchard.apiary.BeeProvisioningException;
import dev.orchard.core.model.Bee;
import dev.orchard.core.model.BeeHealth;
import dev.orchard.core.model.BeeSpec;
import dev.orchard.core.model.BeeType;
import dev.orchard.nursery.CommandRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Installs the CLI on the Seedling if missing and writes a workspace-scoped opencode.jsonc
 * config. By default runs `opencode serve` in the background as OpenCode's headless HTTP
 * server; if the bee's spec sets configOverrides["mode"] = "interactive", release() skips
 * starting a process instead, so the cultivator can launch `opencode` themselves from a
 * terminal (VS Code Remote/SSH pane) — e.g. for an interactive TUI session rather than the
 * headless API server.
 */
public class OpencodeBeeKeeper implements BeeKeeper {

    private static final String INSTALL_SCRIPT_URL =
        "https://raw.githubusercontent.com/anomalyco/opencode/main/install.sh";
    private static final String WORKSPACE_ROOT = "/workspace";
    private static final String CONFIG_DIR = WORKSPACE_ROOT + "/.opencode";
    private static final String CONFIG_PATH = CONFIG_DIR + "/opencode.jsonc";
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
    public CompletableFuture<Bee> install(Bee bee, BeeSpec spec, CommandRunner runner) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureBinaryInstalled(runner);
                writeConfig(runner, bee, spec);
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
    public CompletableFuture<Bee> release(Bee bee, CommandRunner runner) {
        return CompletableFuture.supplyAsync(() -> {
            if (isInteractiveMode(bee)) {
                return bee;
            }
            try {
                String pid = runner.execute(
                    "cd " + WORKSPACE_ROOT + " && nohup opencode serve --port " + SERVER_PORT
                        + " --hostname 127.0.0.1 > /tmp/opencode.log 2>&1 & echo $!");
                return bee.withProcessId(pid.trim());
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
            runner.execute("command -v opencode");
        } catch (IOException e) {
            runner.execute("curl -fsSL " + INSTALL_SCRIPT_URL + " | sh");
        }
    }

    private void writeConfig(CommandRunner runner, Bee bee, BeeSpec spec) throws IOException, InterruptedException {
        String config = renderConfig(bee, spec);
        String encoded = Base64.getEncoder().encodeToString(config.getBytes(StandardCharsets.UTF_8));
        runner.execute("mkdir -p " + CONFIG_DIR + " && echo " + encoded + " | base64 -d > " + CONFIG_PATH);
    }

    private String renderConfig(Bee bee, BeeSpec spec) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("workspace_root", WORKSPACE_ROOT);

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
            runner.execute("opencode --version", 5);
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
