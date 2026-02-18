package dev.orchard.moderne;

import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;
import dev.orchard.core.model.RecipeApplicationJob;
import dev.orchard.core.model.RecipeApplicationState;
import dev.orchard.nursery.SshExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service that applies OpenRewrite recipes to the codebase in a Grove's VM.
 * Detects the build tool (Maven or Gradle) and runs the appropriate OpenRewrite plugin,
 * then captures the resulting git diff and list of changed files.
 */
@Service
public class RecipeApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RecipeApplicationService.class);

    /**
     * Applies an OpenRewrite recipe to the codebase in the given grove asynchronously.
     * The recipe is run via the project's build tool (Maven or Gradle) on the VM.
     *
     * @param grove the grove whose codebase should be transformed
     * @param recipeId the fully qualified OpenRewrite recipe ID
     * @return a CompletableFuture that resolves to the completed job with results
     */
    public CompletableFuture<RecipeApplicationJob> applyRecipe(Grove grove, String recipeId) {
        RecipeApplicationJob job = RecipeApplicationJob.create(grove.id(), recipeId);

        if (grove.state() != GroveState.FLOURISHING || grove.seedling() == null) {
            log.error("Cannot apply recipe to grove {} - grove is not in FLOURISHING state", grove.id());
            return CompletableFuture.completedFuture(
                job.withState(RecipeApplicationState.FAILED));
        }

        return CompletableFuture.supplyAsync(() -> {
            RecipeApplicationJob runningJob = job.withState(RecipeApplicationState.RUNNING);
            try {
                SshExecutor ssh = new SshExecutor(grove.seedling());
                log.info("Applying recipe {} to grove {}", recipeId, grove.id());

                // Detect build tool
                BuildTool buildTool = detectBuildTool(ssh);
                log.info("Detected build tool: {} for grove {}", buildTool, grove.id());

                // Run the recipe
                String command = buildRecipeCommand(buildTool, recipeId);
                log.info("Executing recipe command: {}", command);
                String output = ssh.execute(command);
                log.debug("Recipe execution output: {}", output);

                // Capture changed files
                String changedFilesOutput = ssh.execute("cd /workspace && git diff --name-only");
                List<String> changedFiles = Arrays.stream(changedFilesOutput.split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

                // Capture diff
                String diff = ssh.execute("cd /workspace && git diff");

                log.info("Recipe {} applied to grove {}: {} files changed",
                    recipeId, grove.id(), changedFiles.size());

                return runningJob
                    .withResults(changedFiles, diff)
                    .withState(RecipeApplicationState.COMPLETED);

            } catch (IOException | InterruptedException e) {
                log.error("Failed to apply recipe {} to grove {}: {}",
                    recipeId, grove.id(), e.getMessage(), e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return runningJob.withState(RecipeApplicationState.FAILED);
            }
        });
    }

    /**
     * Commits the changes made by a recipe application in the grove's repository.
     *
     * @param grove the grove whose changes should be committed
     * @param message the commit message
     */
    public void commitChanges(Grove grove, String message) throws IOException, InterruptedException {
        if (grove.seedling() == null) {
            throw new IOException("Grove has no seedling - cannot commit changes");
        }
        SshExecutor ssh = new SshExecutor(grove.seedling());
        ssh.execute("cd /workspace && git add -A");
        ssh.execute(String.format("cd /workspace && git commit -m '%s'",
            message.replace("'", "'\\''")));
        log.info("Committed recipe changes in grove {}: {}", grove.id(), message);
    }

    /**
     * Discards all uncommitted changes in the grove's repository,
     * reverting the effects of a recipe application.
     *
     * @param grove the grove whose changes should be discarded
     */
    public void discardChanges(Grove grove) throws IOException, InterruptedException {
        if (grove.seedling() == null) {
            throw new IOException("Grove has no seedling - cannot discard changes");
        }
        SshExecutor ssh = new SshExecutor(grove.seedling());
        ssh.execute("cd /workspace && git checkout -- . && git clean -fd");
        log.info("Discarded recipe changes in grove {}", grove.id());
    }

    /**
     * Detects whether the project in /workspace uses Maven or Gradle.
     */
    private BuildTool detectBuildTool(SshExecutor ssh) throws IOException, InterruptedException {
        // Check for Gradle first (build.gradle or build.gradle.kts)
        try {
            String result = ssh.execute("test -f /workspace/build.gradle -o -f /workspace/build.gradle.kts && echo 'gradle'");
            if (result.trim().equals("gradle")) {
                return BuildTool.GRADLE;
            }
        } catch (IOException ignored) {
            // File doesn't exist, try Maven
        }

        // Check for Maven (pom.xml)
        try {
            String result = ssh.execute("test -f /workspace/pom.xml && echo 'maven'");
            if (result.trim().equals("maven")) {
                return BuildTool.MAVEN;
            }
        } catch (IOException ignored) {
            // File doesn't exist
        }

        // Default to Maven if no build file found
        log.warn("No build tool detected in /workspace, defaulting to Maven");
        return BuildTool.MAVEN;
    }

    /**
     * Builds the shell command to run an OpenRewrite recipe using the detected build tool.
     */
    private String buildRecipeCommand(BuildTool buildTool, String recipeId) {
        return switch (buildTool) {
            case MAVEN -> String.format(
                "cd /workspace && mvn -U org.openrewrite.maven:rewrite-maven-plugin:run -Drewrite.activeRecipes=%s",
                recipeId);
            case GRADLE -> String.format(
                "cd /workspace && ./gradlew rewriteRun -Drewrite.activeRecipe=%s",
                recipeId);
        };
    }

    private enum BuildTool {
        MAVEN, GRADLE
    }
}
