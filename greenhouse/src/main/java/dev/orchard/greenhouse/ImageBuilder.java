package dev.orchard.greenhouse;

import dev.orchard.core.model.LifecycleCommand;
import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.Seed;
import dev.orchard.greenhouse.config.GreenhouseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Builds Docker images from devcontainer specifications and pushes them to a registry.
 * The ImageBuilder is the core tool in the Greenhouse, responsible for
 * cultivating container images from Seeds.
 */
@Component
public class ImageBuilder {

    private static final Logger log = LoggerFactory.getLogger(ImageBuilder.class);
    private static final int BUILD_TIMEOUT_MINUTES = 30;
    private static final int PUSH_TIMEOUT_MINUTES = 15;

    private final GreenhouseConfig config;

    public ImageBuilder(GreenhouseConfig config) {
        this.config = config;
    }

    /**
     * Builds a Docker image for the given repository, branch, and seed specification.
     * Returns the image reference (tag) that can be used to pull/run the image.
     */
    public String buildImage(String repositoryUrl, String branch, DevcontainerSeed seed, Path repoDir) throws IOException, InterruptedException {
        String imageName = generateImageName(repositoryUrl, branch);
        String imageRef = config.imageReference(imageName);

        log.info("Building image {} for repo {} branch {}", imageRef, repositoryUrl, branch);

        if (seed.dockerfilePath() != null) {
            buildFromDockerfile(repoDir, seed, imageRef);
        } else if (seed.image() != null) {
            // For image-based seeds, we create a Dockerfile that extends the base image
            // and applies any customizations
            buildFromBaseImage(repoDir, seed, imageRef);
        } else {
            // Fall back to a default dev image
            buildFromBaseImage(repoDir, DevcontainerSeed.devcontainer()
                .image("mcr.microsoft.com/devcontainers/base:ubuntu")
                .build(), imageRef);
        }

        log.info("Successfully built image {}", imageRef);
        return imageRef;
    }

    /**
     * Pushes a built image to the configured registry.
     */
    public void pushImage(String imageRef) throws IOException, InterruptedException {
        log.info("Pushing image {} to registry", imageRef);

        // Login to registry if credentials are configured
        loginToRegistry();

        List<String> command = List.of("docker", "push", imageRef);
        int exitCode = executeCommand(command, null, PUSH_TIMEOUT_MINUTES);

        if (exitCode != 0) {
            throw new IOException("Failed to push image " + imageRef + ", exit code: " + exitCode);
        }

        log.info("Successfully pushed image {}", imageRef);
    }

    /**
     * Clones a git repository to the specified directory.
     */
    public Path cloneRepository(String repositoryUrl, String branch, Path workDir) throws IOException, InterruptedException {
        Path repoDir = workDir.resolve("repo");
        Files.createDirectories(repoDir);

        log.info("Cloning {} branch {} to {}", repositoryUrl, branch, repoDir);

        List<String> command = List.of(
            "git", "clone", "--branch", branch, "--depth", "1", repositoryUrl, repoDir.toString()
        );

        int exitCode = executeCommand(command, workDir, 5);
        if (exitCode != 0) {
            throw new IOException("Failed to clone repository " + repositoryUrl + ", exit code: " + exitCode);
        }

        return repoDir;
    }

    /**
     * Gets the current commit SHA of a cloned repository.
     */
    public String getCommitSha(Path repoDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
        pb.directory(repoDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.readLine();
        }

        process.waitFor(30, TimeUnit.SECONDS);
        return output != null ? output.trim() : null;
    }

    private void buildFromDockerfile(Path repoDir, DevcontainerSeed seed, String imageRef) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("build");
        command.add("-t");
        command.add(imageRef);

        // Add build args
        if (seed.buildArgs() != null) {
            seed.buildArgs().forEach((key, value) -> {
                command.add("--build-arg");
                command.add(key + "=" + value);
            });
        }

        // Resolve Dockerfile path relative to repo
        String dockerfilePath = seed.dockerfilePath();
        Path dockerfileAbsolute = repoDir.resolve(".devcontainer").resolve(dockerfilePath);
        if (Files.exists(dockerfileAbsolute)) {
            command.add("-f");
            command.add(dockerfileAbsolute.toString());
        }

        command.add(repoDir.toString());

        int exitCode = executeCommand(command, repoDir, BUILD_TIMEOUT_MINUTES);
        if (exitCode != 0) {
            throw new IOException("Docker build failed with exit code: " + exitCode);
        }
    }

    private void buildFromBaseImage(Path repoDir, DevcontainerSeed seed, String imageRef) throws IOException, InterruptedException {
        // Generate a Dockerfile from the seed's image specification
        Path dockerfilePath = repoDir.resolve(".orchard-prebuild.Dockerfile");

        StringBuilder dockerfile = new StringBuilder();
        dockerfile.append("FROM ").append(seed.image()).append("\n");

        // Add container environment variables
        if (seed.containerEnv() != null) {
            seed.containerEnv().forEach((key, value) ->
                dockerfile.append("ENV ").append(key).append("=").append(value).append("\n"));
        }

        // Run post-create command during build
        if (seed.postCreateCommand() != null) {
            switch (seed.postCreateCommand()) {
                case LifecycleCommand.Sequential s ->
                    dockerfile.append("RUN ").append(String.join(" ", s.args())).append("\n");
                case LifecycleCommand.Parallel p ->
                    p.steps().values().forEach(args ->
                        dockerfile.append("RUN ").append(String.join(" ", args)).append("\n"));
            }
        }

        Files.writeString(dockerfilePath, dockerfile.toString());

        List<String> command = List.of(
            "docker", "build", "-t", imageRef, "-f", dockerfilePath.toString(), repoDir.toString()
        );

        int exitCode = executeCommand(command, repoDir, BUILD_TIMEOUT_MINUTES);
        if (exitCode != 0) {
            throw new IOException("Docker build from base image failed with exit code: " + exitCode);
        }

        // Clean up generated Dockerfile
        Files.deleteIfExists(dockerfilePath);
    }

    private void loginToRegistry() throws IOException, InterruptedException {
        if (config.username() == null || config.username().isBlank()) {
            return;
        }

        log.debug("Logging in to registry {}", config.registryUrl());

        ProcessBuilder pb = new ProcessBuilder(
            "docker", "login", config.registryUrl(),
            "-u", config.username(), "--password-stdin"
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        process.getOutputStream().write(config.password().getBytes());
        process.getOutputStream().close();

        process.waitFor(30, TimeUnit.SECONDS);
        if (process.exitValue() != 0) {
            log.warn("Docker registry login failed for {}", config.registryUrl());
        }
    }

    private String generateImageName(String repositoryUrl, String branch) {
        // Extract repo name from URL
        String name = repositoryUrl;
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }

        // Sanitize for use as Docker tag
        String sanitizedBranch = branch.replaceAll("[^a-zA-Z0-9._-]", "-");
        return "orchard-prebuild/" + name.toLowerCase() + ":" + sanitizedBranch;
    }

    private int executeCommand(List<String> command, Path workDir, int timeoutMinutes) throws IOException, InterruptedException {
        log.debug("Executing: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Read output in background to prevent buffer deadlock
        Thread outputReader = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[cmd] {}", line);
                }
            } catch (IOException e) {
                log.warn("Error reading command output", e);
            }
        });

        boolean completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        outputReader.join(5000);

        if (!completed) {
            process.destroyForcibly();
            throw new IOException("Command timed out after " + timeoutMinutes + " minutes");
        }

        return process.exitValue();
    }
}
