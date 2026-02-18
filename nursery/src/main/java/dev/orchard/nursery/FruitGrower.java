package dev.orchard.nursery;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.FruitState;
import dev.orchard.core.model.Seed;
import dev.orchard.core.model.Seedling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Grows Fruit (containers) on Seedlings (VMs).
 * Executes Docker commands on the VM via SSH to manage containers.
 */
public class FruitGrower {

    private static final Logger log = LoggerFactory.getLogger(FruitGrower.class);

    private final ExecutorService executor;

    public FruitGrower() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Grows a fruit (starts a devcontainer) on the given seedling.
     */
    public CompletableFuture<Fruit> grow(Seedling seedling, Fruit fruit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Growing fruit {} on seedling {}", fruit.id(), seedling.id());

                Seed seed = fruit.seed();
                String containerId;

                if (seed.image() != null) {
                    // Pull and run pre-built image
                    containerId = runFromImage(seedling, fruit);
                } else if (seed.dockerfilePath() != null) {
                    // Build from Dockerfile and run
                    containerId = buildAndRun(seedling, fruit);
                } else {
                    throw new IllegalArgumentException("Seed must have either image or dockerfilePath");
                }

                // Get port mappings
                List<Fruit.PortMapping> ports = getPortMappings(seedling, containerId);

                // Run post-create commands
                if (seed.postCreateCommands() != null) {
                    for (String cmd : seed.postCreateCommands()) {
                        executeInContainer(seedling, containerId, cmd);
                    }
                }

                return fruit
                    .withContainerDetails(containerId, ports)
                    .withState(FruitState.RIPE);

            } catch (Exception e) {
                log.error("Failed to grow fruit {}", fruit.id(), e);
                return fruit.withState(FruitState.ROTTED);
            }
        }, executor);
    }

    /**
     * Grows multiple fruits via Docker Compose on the given seedling.
     * Runs `docker compose -f <file> up -d` and maps running containers to Fruit records.
     *
     * @param seedling the VM to run the compose stack on
     * @param fruits the fruit records to map to compose services
     * @param composeFile the path to the docker-compose file on the VM (relative to /workspace)
     * @return a future with the updated list of fruits with container details and RIPE state
     */
    public CompletableFuture<List<Fruit>> growCompose(Seedling seedling, List<Fruit> fruits, String composeFile) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Growing {} fruits via Docker Compose on seedling {}", fruits.size(), seedling.id());

                // Run docker compose up
                String composePath = composeFile.startsWith("/") ? composeFile : "/workspace/" + composeFile;
                executeSsh(seedling, "docker compose -f " + composePath + " up -d");

                // Get running containers from the compose project
                String psOutput = executeSsh(seedling,
                    "docker compose -f " + composePath + " ps --format '{{.ID}}|{{.Service}}|{{.Name}}'");

                // Map running containers to fruits by service name
                List<Fruit> grownFruits = new ArrayList<>();
                for (Fruit fruit : fruits) {
                    String serviceName = fruit.serviceName();
                    if (serviceName == null) {
                        // If no service name, try matching by container name
                        serviceName = fruit.containerName();
                    }

                    String containerId = null;
                    for (String line : psOutput.split("\n")) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;
                        String[] parts = trimmed.split("\\|");
                        if (parts.length >= 2) {
                            String lineContainerId = parts[0].trim();
                            String lineService = parts[1].trim();
                            if (lineService.equals(serviceName)) {
                                containerId = lineContainerId;
                                break;
                            }
                        }
                    }

                    if (containerId != null) {
                        List<Fruit.PortMapping> ports = getPortMappings(seedling, containerId);
                        // Run post-create commands for the primary service
                        if (fruit.seed() != null && fruit.seed().postCreateCommands() != null) {
                            for (String cmd : fruit.seed().postCreateCommands()) {
                                executeInContainer(seedling, containerId, cmd);
                            }
                        }
                        grownFruits.add(fruit
                            .withContainerDetails(containerId, ports)
                            .withState(FruitState.RIPE));
                    } else {
                        log.warn("No container found for service '{}' in compose output", serviceName);
                        grownFruits.add(fruit.withState(FruitState.ROTTED));
                    }
                }

                return grownFruits;

            } catch (Exception e) {
                log.error("Failed to grow compose stack on seedling {}", seedling.id(), e);
                return fruits.stream()
                    .map(f -> f.withState(FruitState.ROTTED))
                    .toList();
            }
        }, executor);
    }

    /**
     * Picks a fruit (stops the container).
     */
    public CompletableFuture<Fruit> pick(Seedling seedling, Fruit fruit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Picking fruit {}", fruit.id());
                executeSsh(seedling, "docker stop " + fruit.containerId());
                return fruit.withState(FruitState.PICKED);
            } catch (Exception e) {
                log.error("Failed to pick fruit {}", fruit.id(), e);
                return fruit;
            }
        }, executor);
    }

    /**
     * Composts a fruit (removes the container entirely).
     */
    public CompletableFuture<Void> compost(Seedling seedling, Fruit fruit) {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("Composting fruit {}", fruit.id());
                executeSsh(seedling, "docker rm -f " + fruit.containerId());
            } catch (Exception e) {
                log.error("Failed to compost fruit {}", fruit.id(), e);
            }
        }, executor);
    }

    private String runFromImage(Seedling seedling, Fruit fruit) throws IOException, InterruptedException {
        Seed seed = fruit.seed();

        // Pull the image
        executeSsh(seedling, "docker pull " + seed.image());

        // Build docker run command
        StringBuilder cmd = new StringBuilder("docker run -d");
        cmd.append(" --name ").append(fruit.containerName());

        // Add environment variables
        if (seed.containerEnv() != null) {
            for (var entry : seed.containerEnv().entrySet()) {
                cmd.append(" -e ").append(entry.getKey()).append("=").append(entry.getValue());
            }
        }

        // Add port forwards
        if (seed.forwardPorts() != null) {
            for (String port : seed.forwardPorts()) {
                cmd.append(" -p ").append(port).append(":").append(port);
            }
        }

        // Mount workspace
        cmd.append(" -v /workspace:/workspace");
        cmd.append(" -w /workspace");

        cmd.append(" ").append(seed.image());
        cmd.append(" sleep infinity"); // Keep container running

        String output = executeSsh(seedling, cmd.toString());
        return output.trim();
    }

    private String buildAndRun(Seedling seedling, Fruit fruit) throws IOException, InterruptedException {
        Seed seed = fruit.seed();

        // Build the image
        StringBuilder buildCmd = new StringBuilder("docker build");
        buildCmd.append(" -t orchard-fruit-").append(fruit.id().toString().substring(0, 8));

        if (seed.buildArgs() != null) {
            for (var entry : seed.buildArgs().entrySet()) {
                buildCmd.append(" --build-arg ").append(entry.getKey()).append("=").append(entry.getValue());
            }
        }

        buildCmd.append(" -f /workspace/").append(seed.dockerfilePath());
        buildCmd.append(" /workspace");

        executeSsh(seedling, buildCmd.toString());

        // Now run the built image
        Seed imageOnlySeed = Seed.builder()
            .name(seed.name())
            .image("orchard-fruit-" + fruit.id().toString().substring(0, 8))
            .containerEnv(seed.containerEnv())
            .forwardPorts(seed.forwardPorts())
            .postCreateCommands(seed.postCreateCommands())
            .postStartCommands(seed.postStartCommands())
            .build();

        Fruit imageFruit = new Fruit(
            fruit.id(), fruit.groveId(), fruit.seedlingId(),
            null, fruit.containerName(), fruit.serviceName(), imageOnlySeed,
            fruit.state(), fruit.portMappings(), fruit.buddedAt(), fruit.ripenedAt()
        );

        return runFromImage(seedling, imageFruit);
    }

    private List<Fruit.PortMapping> getPortMappings(Seedling seedling, String containerId)
            throws IOException, InterruptedException {
        String output = executeSsh(seedling,
            "docker port " + containerId + " 2>/dev/null || echo ''");

        List<Fruit.PortMapping> mappings = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.contains("->")) {
                // Format: 8080/tcp -> 0.0.0.0:8080
                String[] parts = line.split(" -> ");
                String[] containerPart = parts[0].split("/");
                int containerPort = Integer.parseInt(containerPart[0].trim());
                String protocol = containerPart[1].trim();
                int hostPort = Integer.parseInt(parts[1].split(":")[1].trim());
                mappings.add(new Fruit.PortMapping(containerPort, hostPort, protocol));
            }
        }
        return mappings;
    }

    private void executeInContainer(Seedling seedling, String containerId, String command)
            throws IOException, InterruptedException {
        executeSsh(seedling, "docker exec " + containerId + " /bin/sh -c '" + command + "'");
    }

    private String executeSsh(Seedling seedling, String command) throws IOException, InterruptedException {
        return new SshExecutor(seedling).execute(command);
    }

    public void shutdown() {
        executor.shutdown();
    }
}
