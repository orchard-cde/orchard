package dev.orchard.nursery.gcp;

import com.google.cloud.compute.v1.*;
import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.nursery.SeedlingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GCP Compute Engine implementation of SeedlingProvider.
 * Provisions GCE instances as Seedlings using startup scripts for initial setup.
 */
public class ComputeSeedlingProvider implements SeedlingProvider {

    private static final Logger log = LoggerFactory.getLogger(ComputeSeedlingProvider.class);
    private static final String PROVIDER_ID = "gcp-compute";

    private final ComputeConfig config;
    private final ExecutorService executor;

    public ComputeSeedlingProvider(ComputeConfig config) {
        this.config = config;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public CompletableFuture<Seedling> plant(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try (InstancesClient instancesClient = InstancesClient.create()) {
                log.info("Planting seedling {} on GCP Compute with spec: {}", seedling.id(), seedling.spec());

                String instanceName = "orchard-" + seedling.id().toString().substring(0, 8);
                String startupScript = buildStartupScript(seedling);

                // Build the instance configuration
                Instance instance = Instance.newBuilder()
                    .setName(instanceName)
                    .setMachineType(config.machineTypeUrl(seedling.spec().cpuCores()))
                    .addDisks(AttachedDisk.newBuilder()
                        .setBoot(true)
                        .setAutoDelete(true)
                        .setInitializeParams(AttachedDiskInitializeParams.newBuilder()
                            .setSourceImage(config.sourceImageUrl())
                            .setDiskSizeGb(seedling.spec().diskGb())
                            .build())
                        .build())
                    .addNetworkInterfaces(NetworkInterface.newBuilder()
                        .setName("global/networks/default")
                        .addAccessConfigs(AccessConfig.newBuilder()
                            .setName("External NAT")
                            .setType("ONE_TO_ONE_NAT")
                            .build())
                        .build())
                    .setMetadata(Metadata.newBuilder()
                        .addItems(Items.newBuilder()
                            .setKey("startup-script")
                            .setValue(startupScript)
                            .build())
                        .addItems(Items.newBuilder()
                            .setKey("orchard-seedling-id")
                            .setValue(seedling.id().toString())
                            .build())
                        .addItems(Items.newBuilder()
                            .setKey("orchard-grove-id")
                            .setValue(seedling.groveId().toString())
                            .build())
                        .build())
                    .putLabels("orchard-managed", "true")
                    .putLabels("orchard-seedling-id", seedling.id().toString().substring(0, 8))
                    .build();

                // Insert the instance
                InsertInstanceRequest insertRequest = InsertInstanceRequest.newBuilder()
                    .setProject(config.project())
                    .setZone(config.zone())
                    .setInstanceResource(instance)
                    .build();

                Operation operation = instancesClient.insertAsync(insertRequest).get();
                log.info("GCE instance {} creation completed with status: {}", instanceName, operation.getStatus());

                Seedling sprouting = seedling
                    .withProviderDetails(instanceName, null)
                    .withState(SeedlingState.SPROUTING);

                // Get the instance to retrieve its IP
                Instance created = instancesClient.get(config.project(), config.zone(), instanceName);
                String internalIp = null;
                if (!created.getNetworkInterfacesList().isEmpty()) {
                    internalIp = created.getNetworkInterfaces(0).getNetworkIP();
                }

                log.info("GCE instance {} is running with IP {}", instanceName, internalIp);

                return new Seedling(
                    seedling.id(),
                    seedling.groveId(),
                    instanceName,
                    internalIp,
                    22,
                    SeedlingState.SAPLING,
                    seedling.spec(),
                    seedling.plantedAt(),
                    Instant.now()
                );

            } catch (Exception e) {
                log.error("Failed to plant seedling {} on GCP Compute", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Seedling> water(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try (InstancesClient instancesClient = InstancesClient.create()) {
                String instanceName = seedling.providerInstanceId();
                log.info("Watering (starting) GCE instance {} for seedling {}", instanceName, seedling.id());

                StartInstanceRequest request = StartInstanceRequest.newBuilder()
                    .setProject(config.project())
                    .setZone(config.zone())
                    .setInstance(instanceName)
                    .build();

                instancesClient.startAsync(request).get();

                // Refresh IP address
                Instance instance = instancesClient.get(config.project(), config.zone(), instanceName);
                String internalIp = null;
                if (!instance.getNetworkInterfacesList().isEmpty()) {
                    internalIp = instance.getNetworkInterfaces(0).getNetworkIP();
                }

                return new Seedling(
                    seedling.id(),
                    seedling.groveId(),
                    instanceName,
                    internalIp,
                    22,
                    SeedlingState.SAPLING,
                    seedling.spec(),
                    seedling.plantedAt(),
                    Instant.now()
                );

            } catch (Exception e) {
                log.error("Failed to water seedling {} on GCP Compute", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Seedling> dormant(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try (InstancesClient instancesClient = InstancesClient.create()) {
                String instanceName = seedling.providerInstanceId();
                log.info("Setting seedling {} (instance {}) to dormant (stopping)", seedling.id(), instanceName);

                StopInstanceRequest request = StopInstanceRequest.newBuilder()
                    .setProject(config.project())
                    .setZone(config.zone())
                    .setInstance(instanceName)
                    .build();

                instancesClient.stopAsync(request).get();

                return seedling.withState(SeedlingState.WILTING);

            } catch (Exception e) {
                log.error("Failed to set seedling {} dormant on GCP Compute", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> uproot(Seedling seedling) {
        return CompletableFuture.runAsync(() -> {
            try (InstancesClient instancesClient = InstancesClient.create()) {
                String instanceName = seedling.providerInstanceId();
                log.info("Uprooting seedling {} (deleting GCE instance {})", seedling.id(), instanceName);

                DeleteInstanceRequest request = DeleteInstanceRequest.newBuilder()
                    .setProject(config.project())
                    .setZone(config.zone())
                    .setInstance(instanceName)
                    .build();

                instancesClient.deleteAsync(request).get();
                log.info("GCE instance {} deleted for seedling {}", instanceName, seedling.id());

            } catch (Exception e) {
                log.error("Failed to uproot seedling {} on GCP Compute", seedling.id(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Seedling> inspect(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try (InstancesClient instancesClient = InstancesClient.create()) {
                String instanceName = seedling.providerInstanceId();

                Instance instance = instancesClient.get(config.project(), config.zone(), instanceName);
                SeedlingState state = mapGceStatus(instance.getStatus());

                String internalIp = null;
                if (!instance.getNetworkInterfacesList().isEmpty()) {
                    internalIp = instance.getNetworkInterfaces(0).getNetworkIP();
                }

                return new Seedling(
                    seedling.id(),
                    seedling.groveId(),
                    instanceName,
                    internalIp,
                    22,
                    state,
                    seedling.spec(),
                    seedling.plantedAt(),
                    seedling.readyAt()
                );

            } catch (Exception e) {
                log.error("Failed to inspect seedling {} on GCP Compute", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public boolean isAvailable() {
        try (InstancesClient instancesClient = InstancesClient.create()) {
            ListInstancesRequest request = ListInstancesRequest.newBuilder()
                .setProject(config.project())
                .setZone(config.zone())
                .setMaxResults(1)
                .build();
            instancesClient.list(request);
            return true;
        } catch (Exception e) {
            log.warn("GCP Compute provider is not available: {}", e.getMessage());
            return false;
        }
    }

    private SeedlingState mapGceStatus(String gceStatus) {
        return switch (gceStatus) {
            case "PROVISIONING", "STAGING" -> SeedlingState.SPROUTING;
            case "RUNNING" -> SeedlingState.SAPLING;
            case "STOPPING", "STOPPED", "SUSPENDING", "SUSPENDED" -> SeedlingState.WILTING;
            case "TERMINATED" -> SeedlingState.WITHERED;
            default -> SeedlingState.BLIGHTED;
        };
    }

    private String buildStartupScript(Seedling seedling) {
        return """
            #!/bin/bash
            set -e

            # Create cultivator user
            useradd -m -s /bin/bash cultivator || true
            echo "cultivator ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/cultivator

            # Install Docker
            apt-get update -y
            apt-get install -y docker.io git curl
            systemctl enable docker
            systemctl start docker
            usermod -aG docker cultivator

            # Prepare workspace
            mkdir -p /workspace
            chown cultivator:cultivator /workspace
            """;
    }
}
