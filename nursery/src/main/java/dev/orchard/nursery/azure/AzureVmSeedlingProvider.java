package dev.orchard.nursery.azure;

import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.compute.ComputeManager;
import com.azure.resourcemanager.compute.models.ImageReference;
import com.azure.resourcemanager.compute.models.PowerState;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.compute.models.VirtualMachineSizeTypes;
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
 * Azure VM implementation of SeedlingProvider.
 * Provisions Azure Virtual Machines as Seedlings using cloud-init for initial setup.
 */
public class AzureVmSeedlingProvider implements SeedlingProvider {

    private static final Logger log = LoggerFactory.getLogger(AzureVmSeedlingProvider.class);
    private static final String PROVIDER_ID = "azure-vm";

    private final AzureConfig config;
    private final ComputeManager computeManager;
    private final ExecutorService executor;

    public AzureVmSeedlingProvider(AzureConfig config) {
        this.config = config;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();

        AzureProfile profile = new AzureProfile(
            null,
            config.subscriptionId(),
            AzureEnvironment.AZURE
        );

        this.computeManager = ComputeManager.authenticate(
            new DefaultAzureCredentialBuilder().build(),
            profile
        );
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public CompletableFuture<Seedling> plant(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Planting seedling {} on Azure with spec: {}", seedling.id(), seedling.spec());

                String vmName = "orchard-" + seedling.id().toString().substring(0, 8);
                String vmSize = config.resolveVmSize(seedling.spec().cpuCores());
                String cloudInit = buildCloudInitData(seedling);

                // Use Ubuntu 22.04 LTS via specific image reference
                ImageReference ubuntuImage = new ImageReference()
                    .withPublisher("Canonical")
                    .withOffer("0001-com-ubuntu-server-jammy")
                    .withSku("22_04-lts-gen2")
                    .withVersion("latest");

                VirtualMachine vm = computeManager.virtualMachines()
                    .define(vmName)
                    .withRegion(config.location())
                    .withExistingResourceGroup(config.resourceGroup())
                    .withNewPrimaryNetwork("10.0.0.0/24")
                    .withPrimaryPrivateIPAddressDynamic()
                    .withNewPrimaryPublicIPAddress(vmName + "-ip")
                    .withSpecificLinuxImageVersion(ubuntuImage)
                    .withRootUsername("cultivator")
                    .withRootPassword(generateTempPassword())
                    .withCustomData(cloudInit)
                    .withSize(VirtualMachineSizeTypes.fromString(vmSize))
                    .withTag("orchard-managed", "true")
                    .withTag("orchard-seedling-id", seedling.id().toString())
                    .withTag("orchard-grove-id", seedling.groveId().toString())
                    .create();

                String privateIp = vm.getPrimaryNetworkInterface().primaryPrivateIP();
                log.info("Azure VM {} created with private IP {}", vmName, privateIp);

                return new Seedling(
                    seedling.id(),
                    seedling.groveId(),
                    vm.id(),
                    privateIp,
                    22,
                    SeedlingState.SAPLING,
                    seedling.spec(),
                    seedling.plantedAt(),
                    Instant.now()
                );

            } catch (Exception e) {
                log.error("Failed to plant seedling {} on Azure", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Seedling> water(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String vmId = seedling.providerInstanceId();
                log.info("Watering (starting) Azure VM {} for seedling {}", vmId, seedling.id());

                VirtualMachine vm = computeManager.virtualMachines().getById(vmId);
                vm.start();

                String privateIp = vm.getPrimaryNetworkInterface().primaryPrivateIP();

                return new Seedling(
                    seedling.id(),
                    seedling.groveId(),
                    vmId,
                    privateIp,
                    22,
                    SeedlingState.SAPLING,
                    seedling.spec(),
                    seedling.plantedAt(),
                    Instant.now()
                );

            } catch (Exception e) {
                log.error("Failed to water seedling {} on Azure", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Seedling> dormant(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String vmId = seedling.providerInstanceId();
                log.info("Setting seedling {} (VM {}) to dormant (deallocating)", seedling.id(), vmId);

                VirtualMachine vm = computeManager.virtualMachines().getById(vmId);
                vm.deallocate();

                return seedling.withState(SeedlingState.WILTING);

            } catch (Exception e) {
                log.error("Failed to set seedling {} dormant on Azure", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> uproot(Seedling seedling) {
        return CompletableFuture.runAsync(() -> {
            try {
                String vmId = seedling.providerInstanceId();
                log.info("Uprooting seedling {} (deleting Azure VM {})", seedling.id(), vmId);

                computeManager.virtualMachines().deleteById(vmId);
                log.info("Azure VM {} deleted for seedling {}", vmId, seedling.id());

            } catch (Exception e) {
                log.error("Failed to uproot seedling {} on Azure", seedling.id(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Seedling> inspect(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String vmId = seedling.providerInstanceId();
                VirtualMachine vm = computeManager.virtualMachines().getById(vmId);

                if (vm == null) {
                    return seedling.withState(SeedlingState.WITHERED);
                }

                SeedlingState state = mapAzurePowerState(vm.powerState());
                String privateIp = vm.getPrimaryNetworkInterface().primaryPrivateIP();

                return new Seedling(
                    seedling.id(),
                    seedling.groveId(),
                    vmId,
                    privateIp,
                    22,
                    state,
                    seedling.spec(),
                    seedling.plantedAt(),
                    seedling.readyAt()
                );

            } catch (Exception e) {
                log.error("Failed to inspect seedling {} on Azure", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public boolean isAvailable() {
        try {
            computeManager.virtualMachines()
                .listByResourceGroup(config.resourceGroup());
            return true;
        } catch (Exception e) {
            log.warn("Azure VM provider is not available: {}", e.getMessage());
            return false;
        }
    }

    private SeedlingState mapAzurePowerState(PowerState powerState) {
        if (powerState == null) {
            return SeedlingState.BLIGHTED;
        }
        if (PowerState.RUNNING.equals(powerState)) {
            return SeedlingState.SAPLING;
        }
        if (PowerState.STARTING.equals(powerState)) {
            return SeedlingState.SPROUTING;
        }
        if (PowerState.STOPPED.equals(powerState) || PowerState.STOPPING.equals(powerState)
                || PowerState.DEALLOCATED.equals(powerState) || PowerState.DEALLOCATING.equals(powerState)) {
            return SeedlingState.WILTING;
        }
        return SeedlingState.BLIGHTED;
    }

    private String buildCloudInitData(Seedling seedling) {
        return """
            #cloud-config
            users:
              - name: cultivator
                sudo: ALL=(ALL) NOPASSWD:ALL
                shell: /bin/bash
                groups: docker
            packages:
              - docker.io
              - git
              - curl
            runcmd:
              - systemctl enable docker
              - systemctl start docker
              - usermod -aG docker cultivator
              - mkdir -p /workspace
              - chown cultivator:cultivator /workspace
            """;
    }

    /**
     * Generates a temporary password that meets Azure complexity requirements.
     * In production, SSH keys should be used instead.
     */
    private String generateTempPassword() {
        return "Orchard-" + java.util.UUID.randomUUID().toString().substring(0, 12) + "!";
    }
}
