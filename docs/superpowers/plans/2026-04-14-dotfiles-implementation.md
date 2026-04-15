# User Dotfile Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Gitpod-style dotfiles support — cultivators provide a Git repository URL, Orchard clones it and runs an install script on workspace creation.

**Architecture:** Add `DotfilesConfig` to the `Cultivator` model, create `DotfilesService` for installation, integrate into `GroveService.provisionGrove()` after repository clone. CLI support in Trowel. REST API is deferred to a follow-up issue.

**Prerequisite:** Issue #4 (multi-devcontainer) should be completed first, as dotfiles may leverage multi-container patterns.

**Tech Stack:** Java 21, SSH over SshExecutor, Flyway migrations, JUnit 5 + AssertJ, Picocli

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `roots/src/main/resources/db/migration/V6__add_dotfiles_support.sql` | Create | Database schema |
| `core/src/main/java/dev/orchard/core/model/DotfilesConfig.java` | Create | DotfilesConfig record |
| `core/src/main/java/dev/orchard/core/model/Cultivator.java` | Modify | Add dotfiles field |
| `core/src/main/java/dev/orchard/core/model/DotfilesInstallResult.java` | Create | Installation result |
| `api/src/main/java/dev/orchard/api/service/DotfilesService.java` | Create | Clone + install logic |
| `api/src/main/java/dev/orchard/api/service/GroveService.java` | Modify | Add dotfiles step |
| `trowel/src/main/java/dev/orchard/trowel/command/ConfigCommand.java` | Modify | Add dotfiles subcommands |
| `roots/src/test/java/dev/orchard/roots/repository/CultivatorRepositoryTest.java` | Modify | Add dotfiles query tests |

---

## Task 1: Database migration

**Files:**
- Create: `roots/src/main/resources/db/migration/V6__add_dotfiles_support.sql`

- [ ] **Step 1: Create migration file**

```sql
ALTER TABLE cultivators 
ADD COLUMN dotfiles_repository VARCHAR(1024);

ALTER TABLE cultivators 
ADD COLUMN dotfiles_enabled BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX idx_cultivators_dotfiles_enabled 
ON cultivators(dotfiles_enabled) 
WHERE dotfiles_enabled = true;

COMMENT ON COLUMN cultivators.dotfiles_repository IS 'Git repository URL for user dotfiles';
COMMENT ON COLUMN cultivators.dotfiles_enabled IS 'Whether dotfiles installation is enabled';
```

- [ ] **Step 2: Verify migration syntax**

Run against a test database or use Flyway's dry-run capability.

- [ ] **Step 3: Commit**

```bash
git add roots/src/main/resources/db/migration/V6__add_dotfiles_support.sql
git commit -m "feat: add dotfiles columns to cultivators table"
```

---

## Task 2: Add DotfilesConfig to core model

**Files:**
- Create: `core/src/main/java/dev/orchard/core/model/DotfilesConfig.java`
- Modify: `core/src/main/java/dev/orchard/core/model/Cultivator.java`

- [ ] **Step 1: Create DotfilesConfig record**

```java
package dev.orchard.core.model;

public record DotfilesConfig(
    String repositoryUrl,
    boolean enabled
) {
    public static DotfilesConfig empty() {
        return new DotfilesConfig(null, false);
    }

    public static DotfilesConfig of(String repositoryUrl) {
        return new DotfilesConfig(repositoryUrl, true);
    }

    public boolean hasRepository() {
        return repositoryUrl != null && !repositoryUrl.isBlank();
    }

    public DotfilesConfig withEnabled(boolean enabled) {
        return new DotfilesConfig(repositoryUrl, enabled);
    }

    public DotfilesConfig withRepository(String repositoryUrl) {
        return new DotfilesConfig(repositoryUrl, enabled);
    }
}
```

- [ ] **Step 2: Create DotfilesInstallResult record**

```java
package dev.orchard.core.model;

import java.time.Duration;
import java.util.Optional;

public record DotfilesInstallResult(
    boolean success,
    String scriptUsed,
    int exitCode,
    String outputLog,
    Duration duration,
    Optional<String> errorMessage
) {
    public static DotfilesInstallResult skipped() {
        return new DotfilesInstallResult(
            true, null, 0, "No dotfiles configured", 
            Duration.ZERO, Optional.empty()
        );
    }

    public static DotfilesInstallResult of(String script, int exitCode, String log) {
        return new DotfilesInstallResult(
            exitCode == 0,
            script,
            exitCode,
            log,
            Duration.ZERO,
            exitCode == 0 ? Optional.empty() : Optional.of("Script exited with code " + exitCode)
        );
    }
}
```

- [ ] **Step 3: Modify Cultivator record**

Add `dotfiles` field to Cultivator:

```java
public record Cultivator(
    UUID id,
    String username,
    String email,
    String provider,
    String providerId,
    String avatarUrl,
    String displayName,
    DotfilesConfig dotfiles,  // NEW
    Instant createdAt,
    Instant lastActiveAt
) {
    public static Cultivator create(String username, String email, String provider) {
        return new Cultivator(
            UUID.randomUUID(),
            username,
            email,
            provider,
            null,
            null,
            username,
            DotfilesConfig.empty(),  // NEW
            Instant.now(),
            Instant.now()
        );
    }

    public Cultivator withDotfiles(DotfilesConfig dotfiles) {
        return new Cultivator(
            id, username, email, provider, providerId,
            avatarUrl, displayName, dotfiles,
            createdAt, Instant.now()
        );
    }
}
```

- [ ] **Step 4: Verify compilation**

```bash
./gradlew :core:compileJava --console=plain
```

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/orchard/core/model/DotfilesConfig.java
git add core/src/main/java/dev/orchard/core/model/DotfilesInstallResult.java
git add core/src/main/java/dev/orchard/core/model/Cultivator.java
git commit -m "feat: add DotfilesConfig and DotfilesInstallResult to core model"
```

---

## Task 3: Create DotfilesService

**Files:**
- Create: `api/src/main/java/dev/orchard/api/service/DotfilesService.java`

- [ ] **Step 1: Create the service**

```java
package dev.orchard.api.service;

import dev.orchard.core.model.DotfilesConfig;
import dev.orchard.core.model.DotfilesInstallResult;
import dev.orchard.nursery.Seedling;
import dev.orchard.nursery.SshExecutor;
import dev.orchard.nursery.SshResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
class DotfilesService {

    private static final Logger log = LoggerFactory.getLogger(DotfilesService.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private static final List<String> INSTALL_SCRIPT_NAMES = List.of(
        "install.sh", "install",
        "bootstrap.sh", "bootstrap",
        "setup.sh", "setup",
        "init.sh", "init"
    );

    private final SshExecutor ssh;
    private final Duration timeout;

    DotfilesService(
            SshExecutor ssh,
            @Value("${orchard.dotfiles.timeout-seconds:300}") int timeoutSeconds) {
        this.ssh = ssh;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public DotfilesInstallResult install(Seedling seedling, DotfilesConfig config) {
        if (!config.enabled() || !config.hasRepository()) {
            log.debug("Dotfiles not configured for cultivator");
            return DotfilesInstallResult.skipped();
        }

        log.info("Installing dotfiles from {} for seedling {}", config.repositoryUrl(), seedling.id());
        Instant start = Instant.now();

        try {
            String dotfilesDir = "/workspace/.orchard/dotfiles";
            String logFile = "/workspace/.orchard/dotfiles.log";

            // 1. Clone repository
            String cloneCmd = String.format(
                "mkdir -p /workspace/.orchard && " +
                "git clone --depth 1 %s %s",
                config.repositoryUrl(), dotfilesDir
            );
            SshResult cloneResult = ssh.executeWithTimeout(seedling, cloneCmd, timeout);
            if (cloneResult.exitCode() != 0) {
                return DotfilesInstallResult.of(
                    "git clone", 
                    cloneResult.exitCode(),
                    "Clone failed: " + cloneResult.stderr()
                );
            }

            // 2. Detect install script
            String script = detectInstallScript(seedling, dotfilesDir);
            
            if (script == null) {
                // No script found — symlink files to home
                String symlinkCmd = String.format(
                    "for f in %s/*; do " +
                    "[ -f \"$f\" ] && ln -sf \"$f\" ~/; " +
                    "done && echo 0",
                    dotfilesDir
                );
                SshResult symlinkResult = ssh.executeWithTimeout(seedling, symlinkCmd, timeout);
                Duration duration = Duration.between(start, Instant.now());
                return new DotfilesInstallResult(
                    true, "symlink", 0, "No install script, symlinked files",
                    duration, java.util.Optional.empty()
                );
            }

            // 3. Execute install script with timeout
            String execCmd = String.format(
                "cd %s && timeout %d bash %s > %s 2>&1; echo $? >> %s",
                dotfilesDir,
                timeout.getSeconds(),
                script,
                logFile,
                logFile
            );
            SshResult execResult = ssh.executeWithTimeout(seedling, execCmd, timeout.plusSeconds(10));

            // 4. Read log
            String outputLog = ssh.captureFile(seedling, logFile);
            int exitCode = parseExitCode(outputLog);

            Duration duration = Duration.between(start, Instant.now());
            return new DotfilesInstallResult(
                exitCode == 0,
                script,
                exitCode,
                outputLog.length() > 10000 ? outputLog.substring(0, 10000) : outputLog,
                duration,
                exitCode == 0 ? java.util.Optional.empty() : java.util.Optional.of("Exit code: " + exitCode)
            );

        } catch (Exception e) {
            log.error("Dotfiles installation failed", e);
            Duration duration = Duration.between(start, Instant.now());
            return new DotfilesInstallResult(
                false, null, -1, "", duration, java.util.Optional.of(e.getMessage())
            );
        }
    }

    private String detectInstallScript(Seedling seedling, String dotfilesDir) {
        for (String name : INSTALL_SCRIPT_NAMES) {
            String checkCmd = String.format("test -f %s/%s && echo 'found' || echo 'notfound'", dotfilesDir, name);
            SshResult result = ssh.execute(seedling, checkCmd);
            if (result.stdout().contains("found")) {
                return dotfilesDir + "/" + name;
            }
        }
        return null;
    }

    private int parseExitCode(String log) {
        // Exit code is appended to log file on last line
        String[] lines = log.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.matches("\\d+")) {
                return Integer.parseInt(line);
            }
        }
        return 0;
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :api:compileJava --console=plain
```

- [ ] **Step 3: Commit**

```bash
git add api/src/main/java/dev/orchard/api/service/DotfilesService.java
git commit -m "feat: create DotfilesService for dotfiles installation"
```

---

## Task 4: Integrate into GroveService

**Files:**
- Modify: `api/src/main/java/dev/orchard/api/service/GroveService.java`

- [ ] **Step 1: Add dotfiles installation after repo clone**

Find the `provisionGrove()` method and add the dotfiles step:

```java
// Existing steps (simplified):
// 1. plant seedling
// 2. wait for cloud-init
// 3. clone repository
// 4. install dotfiles  ← NEW
// 5. discover devcontainer.json
// 6. grow fruits

// After clone repository step, add:

// 4. Install dotfiles (if configured)
Cultivator cultivator = cultivatorService.findById(grove.cultivatorId())
    .orElseThrow();
if (cultivator.dotfiles().enabled()) {
    log.info("Installing dotfiles for grove {}", groveId);
    DotfilesInstallResult result = dotfilesService.install(seedling, cultivator.dotfiles());
    if (result.success()) {
        log.info("Dotfiles installed successfully using {}", result.scriptUsed());
    } else {
        log.warn("Dotfiles installation failed: {}", result.errorMessage().orElse("unknown error"));
        // Don't fail provisioning — dotfiles are optional
    }
}
```

- [ ] **Step 2: Add DotfilesService dependency**

```java
private final DotfilesService dotfilesService;

GroveService(GroveRepository groveRepository, 
             SeedlingService seedlingService,
             CultivatorService cultivatorService,
             DotfilesService dotfilesService,  // NEW
             EventPublisher eventPublisher,
             ProviderRegistry providerRegistry) {
    // ...
}
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :api:compileJava --console=plain
```

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/dev/orchard/api/service/GroveService.java
git commit -m "feat: integrate dotfiles installation into GroveService.provisionGrove()"
```

---

## Task 5: Add CLI commands to Trowel

**Files:**
- Modify: `trowel/src/main/java/dev/orchard/trowel/command/ConfigCommand.java`

- [ ] **Step 1: Add dotfiles subcommands**

```java
@Command(name = "dotfiles-repo", 
         description = "Set dotfiles repository URL",
         subcommands = {})
public class DotfilesRepoCommand implements Callable<Integer> {

    @ParentCommand
    ConfigCommand parent;

    @Parameters(description = "Repository URL (e.g., https://github.com/user/dotfiles)")
    String url;

    @Override
    public Integer call() {
        parent.config.set("dotfiles.repository", url);
        parent.config.save();
        parent.out.println("Dotfiles repository set to: " + url);
        return 0;
    }
}

@Command(name = "dotfiles-enable",
         description = "Enable or disable dotfiles",
         subcommands = {})
public class DotfilesEnableCommand implements Callable<Integer> {

    @ParentCommand
    ConfigCommand parent;

    @Parameters(description = "true or false", arity = "1")
    boolean enabled;

    @Override
    public Integer call() {
        parent.config.set("dotfiles.enabled", String.valueOf(enabled));
        parent.config.save();
        parent.out.println("Dotfiles " + (enabled ? "enabled" : "disabled"));
        return 0;
    }
}

@Command(name = "dotfiles-show",
         description = "Show current dotfiles configuration",
         subcommands = {})
public class DotfilesShowCommand implements Callable<Integer> {

    @ParentCommand
    ConfigCommand parent;

    @Override
    public Integer call() {
        String repo = parent.config.get("dotfiles.repository", "(not set)");
        String enabled = parent.config.get("dotfiles.enabled", "false");
        parent.out.println("Repository: " + repo);
        parent.out.println("Enabled: " + enabled);
        return 0;
    }
}

@Command(name = "dotfiles-clear",
         description = "Remove dotfiles configuration",
         subcommands = {})
public class DotfilesClearCommand implements Callable<Integer> {

    @ParentCommand
    ConfigCommand parent;

    @Override
    public Integer call() {
        parent.config.remove("dotfiles.repository");
        parent.config.remove("dotfiles.enabled");
        parent.config.save();
        parent.out.println("Dotfiles configuration cleared");
        return 0;
    }
}
```

- [ ] **Step 2: Register subcommands in ConfigCommand**

```java
@Command(name = "config", description = "Manage configuration")
public class ConfigCommand implements Callable<Integer> {

    // ... existing fields ...

    @Subcommand(mixed = false, 
                commands = {DotfilesRepoCommand.class, 
                           DotfilesEnableCommand.class, 
                           DotfilesShowCommand.class, 
                           DotfilesClearCommand.class})
    DotfilesSubcommands dotfiles;  // NEW
}
```

- [ ] **Step 3: Update REST API call (TBD — deferred to follow-up issue)**

For MVP, dotfiles config is stored in CLI config only. REST API sync is out of scope.

- [ ] **Step 4: Verify compilation**

```bash
./gradlew :trowel:compileJava --console=plain
```

- [ ] **Step 5: Commit**

```bash
git add trowel/src/main/java/dev/orchard/trowel/command/ConfigCommand.java
git commit -m "feat: add dotfiles CLI commands to trowel config"
```

---

## Task 6: Add tests

**Files:**
- Create: `api/src/test/java/dev/orchard/api/service/DotfilesServiceTest.java`

- [ ] **Step 1: Create unit tests**

```java
package dev.orchard.api.service;

import dev.orchard.core.model.DotfilesConfig;
import dev.orchard.core.model.DotfilesInstallResult;
import dev.orchard.nursery.Seedling;
import dev.orchard.nursery.SshExecutor;
import dev.orchard.nursery.SshResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DotfilesServiceTest {

    @Mock
    SshExecutor ssh;

    private DotfilesService service;
    private Seedling seedling;

    @BeforeEach
    void setUp() {
        service = new DotfilesService(ssh, 300);
        seedling = Seedling.germinate(UUID.randomUUID(), null);
    }

    @Test
    void install_whenNotConfigured_returnsSkipped() {
        DotfilesConfig config = DotfilesConfig.empty();

        DotfilesInstallResult result = service.install(seedling, config);

        assertThat(result.success()).isTrue();
        assertThat(result.scriptUsed()).isNull();
        verifyNoInteractions(ssh);
    }

    @Test
    void install_whenDisabled_returnsSkipped() {
        DotfilesConfig config = new DotfilesConfig("https://github.com/user/dotfiles", false);

        DotfilesInstallResult result = service.install(seedling, config);

        assertThat(result.success()).isTrue();
        verifyNoInteractions(ssh);
    }

    @Test
    void install_withRepository_clonesAndRunsScript() {
        DotfilesConfig config = DotfilesConfig.of("https://github.com/user/dotfiles");
        when(ssh.executeWithTimeout(any(), contains("git clone"), any()))
            .thenReturn(new SshResult(0, "", ""));
        when(ssh.execute(any(), contains("test -f")))
            .thenReturn(new SshResult(0, "found", ""));
        when(ssh.executeWithTimeout(any(), contains("timeout"), any()))
            .thenReturn(new SshResult(0, "Script output\n0", ""));
        when(ssh.captureFile(any(), any()))
            .thenReturn("Script output\n0");

        DotfilesInstallResult result = service.install(seedling, config);

        assertThat(result.success()).isTrue();
        assertThat(result.scriptUsed()).contains("install.sh");
        verify(ssh).executeWithTimeout(any(), contains("git clone"), any());
    }

    @Test
    void install_whenCloneFails_returnsFailure() {
        DotfilesConfig config = DotfilesConfig.of("https://github.com/user/dotfiles");
        when(ssh.executeWithTimeout(any(), contains("git clone"), any()))
            .thenReturn(new SshResult(128, "", "fatal: repository not found"));

        DotfilesInstallResult result = service.install(seedling, config);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isPresent();
    }

    @Test
    void install_whenNoScript_symlinksFiles() {
        DotfilesConfig config = DotfilesConfig.of("https://github.com/user/dotfiles");
        when(ssh.executeWithTimeout(any(), contains("git clone"), any()))
            .thenReturn(new SshResult(0, "", ""));
        when(ssh.execute(any(), contains("test -f")))
            .thenReturn(new SshResult(1, "notfound", ""));
        when(ssh.executeWithTimeout(any(), contains("for f in"), any()))
            .thenReturn(new SshResult(0, "", ""));

        DotfilesInstallResult result = service.install(seedling, config);

        assertThat(result.success()).isTrue();
        assertThat(result.scriptUsed()).isEqualTo("symlink");
    }
}
```

- [ ] **Step 2: Run tests**

```bash
./gradlew :api:test --tests "DotfilesServiceTest" --console=plain
```

- [ ] **Step 3: Commit**

```bash
git add api/src/test/java/dev/orchard/api/service/DotfilesServiceTest.java
git commit -m "test: add DotfilesService unit tests"
```

---

## Task 7: Verify full build

- [ ] **Step 1: Run full test suite**

```bash
./gradlew test --console=plain
```

- [ ] **Step 2: Build CLI**

```bash
./gradlew :trowel:fatJar --console=plain
```

- [ ] **Step 3: Manual smoke test**

```bash
java -jar trowel/build/libs/trowel-*-all.jar config dotfiles-show
# Expected: Repository: (not set), Enabled: false
```

---

## Summary

After completion:
- Cultivators can set dotfiles repo via CLI: `trowel config dotfiles-repo <url>`
- Dotfiles install after git clone, before container creation
- Installation failures don't fail grove provisioning
- Full audit logging of installation output
- Unit tests cover core scenarios

**Follow-up:** REST API for dotfiles management (separate issue).
