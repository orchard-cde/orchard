# OpenCode BeeKeeper Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the OpenCode BeeKeeper adapter as the first/foundational AI agent adapter for Orchard.

**Architecture:** The adapter implements the `BeeKeeper` interface from the apiary module. It installs OpenCode via curl, configures it for the workspace, and provides health checking. The adapter is registered in `BeeKeeperRegistry` with highest priority.

**Prerequisites:** Issues #38 (Core Bee models), #39 (BeeKeeper interface), #40 (Bee REST API) must be completed first.

**Tech Stack:** Java 21, SSH over SshExecutor, CompletableFuture, JUnit 5 + AssertJ, Mockito

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `apiary/src/main/java/dev/orchard/apiary/adapter/OpencodeBeeKeeper.java` | Create | OpenCode adapter implementation |
| `apiary/src/test/java/dev/orchard/apiary/adapter/OpencodeBeeKeeperTest.java` | Create | Unit tests with mocked SSH |
| `core/src/main/java/dev/orchard/core/model/BeeType.java` | Modify | Add `OPENCODE` enum value |
| `docs/architecture/bee-architecture.md` | Modify | Add OpenCode to adapter table |

---

## Task 1: Add OPENCODE to BeeType enum

**Files:**
- Modify: `core/src/main/java/dev/orchard/core/model/BeeType.java`

- [ ] **Step 1: Add OPENCODE enum value**

Open `BeeType.java` and add `OPENCODE` after the existing enum values:

```java
public enum BeeType {
    CLAUDE_CODE,
    GEMINI,
    CODEX,
    KIRO,
    OPENCODE,  // NEW
    CUSTOM
}
```

- [ ] **Step 2: Verify the enum is used correctly**

Run `./gradlew :core:compileJava --console=plain` to verify compilation.

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/dev/orchard/core/model/BeeType.java
git commit -m "feat: add OPENCODE to BeeType enum"
```

---

## Task 2: Create OpencodeBeeKeeper adapter

**Files:**
- Create: `apiary/src/main/java/dev/orchard/apiary/adapter/OpencodeBeeKeeper.java`
- Modify: `apiary/src/main/java/dev/orchard/apiary/BeeKeeperRegistry.java` (if needed)

- [ ] **Step 1: Create the adapter class**

```java
package dev.orchard.apiary.adapter;

import dev.orchard.core.model.Bee;
import dev.orchard.core.model.BeeHealth;
import dev.orchard.core.model.BeeSpec;
import dev.orchard.core.model.BeeState;
import dev.orchard.nursery.Seedling;
import dev.orchard.nursery.SshExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
class OpencodeBeeKeeper implements BeeKeeper {

    private static final Logger log = LoggerFactory.getLogger(OpencodeBeeKeeper.class);
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(10);

    private final SshExecutor ssh;

    OpencodeBeeKeeper(SshExecutor ssh) {
        this.ssh = ssh;
    }

    @Override
    public String getBeeType() {
        return "opencode";
    }

    @Override
    public CompletableFuture<Bee> install(Seedling seedling, BeeSpec spec) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Installing OpenCode bee on seedling {}", seedling.id());

            try {
                // 1. Check if opencode is already installed
                if (!isOpencodeInstalled(seedling)) {
                    installOpencodeBinary(seedling);
                }

                // 2. Create .opencode directory
                ssh.execute(seedling, "mkdir -p /workspace/.opencode");

                // 3. Create opencode.json config
                String config = createConfig(seedling, spec);
                ssh.writeFile(seedling, "/workspace/.opencode/opencode.json", config);

                // 4. Start opencode in background
                startOpencode(seedling);

                return Bee.hatch(seedling.groveId(), spec.withType("opencode"));
            } catch (Exception e) {
                log.error("Failed to install OpenCode on seedling {}", seedling.id(), e);
                return Bee.smoked(seedling.groveId(), spec.withType("opencode"), e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Bee> release(Bee bee, Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Releasing OpenCode bee {} on seedling {}", bee.id(), seedling.id());

            try {
                // Kill any running opencode process
                ssh.execute(seedling, "pkill -f 'opencode' || true");
                return bee.withState(BeeState.SMOKED);
            } catch (Exception e) {
                log.error("Failed to release OpenCode on seedling {}", seedling.id(), e);
                return bee;
            }
        });
    }

    @Override
    public CompletableFuture<Bee> smoke(Bee bee, Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var result = ssh.executeWithTimeout(
                    seedling,
                    "opencode --version",
                    HEALTH_CHECK_TIMEOUT
                );
                if (result.exitCode() == 0) {
                    return bee.withState(BeeState.BUZZING);
                }
                return bee.withState(BeeState.SMOKED);
            } catch (Exception e) {
                return bee.withState(BeeState.SMOKED);
            }
        });
    }

    @Override
    public CompletableFuture<BeeHealth> inspect(Bee bee, Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Check if process is running
                var psResult = ssh.execute(seedling, "pgrep -f 'opencode'");
                boolean running = psResult.exitCode() == 0;

                // Check responsiveness
                String version = null;
                if (running) {
                    var versionResult = ssh.executeWithTimeout(
                        seedling,
                        "opencode --version 2>/dev/null",
                        Duration.ofSeconds(5)
                    );
                    if (versionResult.exitCode() == 0) {
                        version = versionResult.stdout().trim();
                    }
                }

                return new BeeHealth(
                    running && version != null,
                    version,
                    running ? Optional.empty() : Optional.of("OpenCode process not found"),
                    Instant.now()
                );
            } catch (Exception e) {
                return new BeeHealth(false, null, Optional.of(e.getMessage()), Instant.now());
            }
        });
    }

    @Override
    public List<String> prerequisites() {
        return List.of("curl", "git");
    }

    private boolean isOpencodeInstalled(Seedling seedling) {
        try {
            var result = ssh.executeWithTimeout(
                seedling,
                "which opencode",
                Duration.ofSeconds(5)
            );
            return result.exitCode() == 0;
        } catch {
            return false;
        }
    }

    private void installOpencodeBinary(Seedling seedling) {
        // Install via official script or direct download
        String installCommand = """
            curl -fsSL https://raw.githubusercontent.com/anomalyco/opencode/main/install.sh | sh
            """;

        var result = ssh.executeWithTimeout(seedling, installCommand, INSTALL_TIMEOUT);
        if (result.exitCode() != 0) {
            throw new RuntimeException("Failed to install OpenCode: " + result.stderr());
        }
    }

    private String createConfig(Seedling seedling, BeeSpec spec) {
        return """
            {
              "workspace_root": "/workspace",
              "orchard": {
                "grove_id": "%s",
                "cultivator_id": "%s"
              }
            }
            """.formatted(seedling.groveId(), spec.cultivatorId());
    }

    private void startOpencode(Seedling seedling) {
        String command = """
            cd /workspace && nohup opencode --headless --project /workspace > /workspace/.opencode/opencode.log 2>&1 &
            sleep 2
            """;
        ssh.execute(seedling, command);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :apiary:compileJava --console=plain
```

Expected: Compiles without errors.

- [ ] **Step 3: Commit**

```bash
git add apiary/src/main/java/dev/orchard/apiary/adapter/OpencodeBeeKeeper.java
git commit -m "feat: implement OpenCode BeeKeeper adapter"
```

---

## Task 3: Create unit tests

**Files:**
- Create: `apiary/src/test/java/dev/orchard/apiary/adapter/OpencodeBeeKeeperTest.java`

- [ ] **Step 1: Create test class**

```java
package dev.orchard.apiary.adapter;

import dev.orchard.core.model.*;
import dev.orchard.nursery.Seedling;
import dev.orchard.nursery.SshExecutor;
import dev.orchard.nursery.SshResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpencodeBeeKeeperTest {

    @Mock
    SshExecutor ssh;

    private OpencodeBeeKeeper keeper;
    private Seedling seedling;
    private BeeSpec spec;

    @BeforeEach
    void setUp() {
        keeper = new OpencodeBeeKeeper(ssh);
        seedling = Seedling.germinate(UUID.randomUUID(), null);
        spec = BeeSpec.of(BeeType.OPENCODE, "latest");
    }

    @Test
    void getBeeType_returnsOpencode() {
        assertThat(keeper.getBeeType()).isEqualTo("opencode");
    }

    @Test
    void prerequisites_returnsCurlAndGit() {
        assertThat(keeper.prerequisites()).containsExactly("curl", "git");
    }

    @Test
    void install_whenNotInstalled_installsAndStarts() {
        when(ssh.executeWithTimeout(any(), eq("which opencode"), any()))
            .thenReturn(new SshResult(1, "", "not found"));
        when(ssh.executeWithTimeout(any(), contains("curl"), any()))
            .thenReturn(new SshResult(0, "Installed", ""));
        when(ssh.execute(any(), anyString()))
            .thenReturn(new SshResult(0, "", ""));

        Bee result = keeper.install(seedling, spec).join();

        assertThat(result.state()).isEqualTo(BeeState.HATCHING);
        verify(ssh).executeWithTimeout(any(), contains("curl"), any());
    }

    @Test
    void install_whenAlreadyInstalled_skipsInstallation() {
        when(ssh.executeWithTimeout(any(), eq("which opencode"), any()))
            .thenReturn(new SshResult(0, "/usr/local/bin/opencode", ""));
        when(ssh.execute(any(), anyString()))
            .thenReturn(new SshResult(0, "", ""));

        Bee result = keeper.install(seedling, spec).join();

        assertThat(result.state()).isEqualTo(BeeState.HATCHING);
        verify(ssh, never()).executeWithTimeout(any(), contains("curl"), any());
    }

    @Test
    void smoke_whenVersionSucceeds_returnsBuzzing() {
        when(ssh.executeWithTimeout(any(), eq("opencode --version"), any()))
            .thenReturn(new SshResult(0, "opencode version 1.0.0", ""));

        Bee bee = Bee.hatching(UUID.randomUUID(), spec);
        Bee result = keeper.smoke(bee, seedling).join();

        assertThat(result.state()).isEqualTo(BeeState.BUZZING);
    }

    @Test
    void inspect_whenRunning_returnsHealthy() {
        when(ssh.execute(any(), eq("pgrep -f 'opencode'")))
            .thenReturn(new SshResult(0, "12345", ""));
        when(ssh.executeWithTimeout(any(), eq("opencode --version"), any()))
            .thenReturn(new SshResult(0, "opencode 1.0.0", ""));

        BeeHealth health = keeper.inspect(Bee.hatching(UUID.randomUUID(), spec), seedling).join();

        assertThat(health.responsive()).isTrue();
        assertThat(health.version()).isEqualTo("opencode 1.0.0");
    }

    @Test
    void inspect_whenNotRunning_returnsUnhealthy() {
        when(ssh.execute(any(), eq("pgrep -f 'opencode'")))
            .thenReturn(new SshResult(1, "", ""));

        BeeHealth health = keeper.inspect(Bee.hatching(UUID.randomUUID(), spec), seedling).join();

        assertThat(health.responsive()).isFalse();
        assertThat(health.errorMessage()).isPresent();
    }
}
```

- [ ] **Step 2: Run tests**

```bash
./gradlew :apiary:test --tests "OpencodeBeeKeeperTest" --console=plain
```

- [ ] **Step 3: Commit**

```bash
git add apiary/src/test/java/dev/orchard/apiary/adapter/OpencodeBeeKeeperTest.java
git commit -m "test: add OpencodeBeeKeeper unit tests"
```

---

## Task 4: Update architecture documentation

**Files:**
- Modify: `docs/architecture/bee-architecture.md`

- [ ] **Step 1: Add OpenCode to adapter table**

Find the "Available BeeKeeper Adapters" table and add OpenCode:

```markdown
| OpenCode | `opencode` | curl install script | curl, git |
```

- [ ] **Step 2: Commit**

```bash
git add docs/architecture/bee-architecture.md
git commit -m "docs: add OpenCode to BeeKeeper adapter table"
```

---

## Task 5: Verify end-to-end

- [ ] **Step 1: Build all modules**

```bash
./gradlew :apiary:build --console=plain
```

- [ ] **Step 2: Run full test suite**

```bash
./gradlew test --console=plain
```

- [ ] **Step 3: Verify BeeKeeperRegistry picks up OpenCode**

Check that `BeeKeeperRegistry` (from #39) includes the OpencodeBeeKeeper:

```bash
./gradlew :apiary:compileJava --console=plain 2>&1 | grep -i opencode
```

Expected: Compiles successfully with OpencodeBeeKeeper registered.

---

## Summary

After completion:
- OpenCode can be attached to any grove: `trowel bee attach --grove <id> --type opencode`
- Bee lifecycle (install, release, smoke, inspect) works via SSH
- Unit tests verify core behavior
- Architecture docs updated
