package dev.orchard.nursery.aws;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.nursery.SeedlingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AWS EC2 implementation of SeedlingProvider.
 * Provisions EC2 instances as Seedlings using cloud-init for initial setup.
 */
public class Ec2SeedlingProvider implements SeedlingProvider {

    private static final Logger log = LoggerFactory.getLogger(Ec2SeedlingProvider.class);
    private static final String PROVIDER_ID = "aws-ec2";

    private final Ec2Config config;
    private final Ec2Client ec2Client;
    private final ExecutorService executor;

    public Ec2SeedlingProvider(Ec2Config config) {
        this.config = config;
        this.ec2Client = Ec2Client.builder()
            .region(Region.of(config.region()))
            .build();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public CompletableFuture<Seedling> plant(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Planting seedling {} on AWS EC2 with spec: {}", seedling.id(), seedling.spec());

                String instanceType = config.resolveInstanceType(seedling.spec().cpuCores());
                String userData = buildCloudInitUserData(seedling);
                String encodedUserData = Base64.getEncoder().encodeToString(userData.getBytes());

                // Launch EC2 instance
                RunInstancesRequest.Builder runRequest = RunInstancesRequest.builder()
                    .imageId(config.amiId())
                    .instanceType(InstanceType.fromValue(instanceType))
                    .minCount(1)
                    .maxCount(1)
                    .userData(encodedUserData)
                    .tagSpecifications(TagSpecification.builder()
                        .resourceType(ResourceType.INSTANCE)
                        .tags(
                            Tag.builder().key("Name").value("orchard-" + seedling.id().toString().substring(0, 8)).build(),
                            Tag.builder().key("orchard:seedling-id").value(seedling.id().toString()).build(),
                            Tag.builder().key("orchard:grove-id").value(seedling.groveId().toString()).build()
                        )
                        .build());

                if (config.keyPairName() != null && !config.keyPairName().isBlank()) {
                    runRequest.keyName(config.keyPairName());
                }
                if (config.securityGroupId() != null && !config.securityGroupId().isBlank()) {
                    runRequest.securityGroupIds(config.securityGroupId());
                }
                if (config.subnetId() != null && !config.subnetId().isBlank()) {
                    runRequest.subnetId(config.subnetId());
                }

                RunInstancesResponse response = ec2Client.runInstances(runRequest.build());
                String instanceId = response.instances().get(0).instanceId();
                log.info("Launched EC2 instance {} for seedling {}", instanceId, seedling.id());

                // Wait for the instance to be running
                Seedling sprouting = seedling
                    .withProviderDetails(instanceId, null)
                    .withState(SeedlingState.SPROUTING);

                log.info("Waiting for instance {} to reach running state", instanceId);
                ec2Client.waiter().waitUntilInstanceRunning(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build()
                );

                // Get the private IP address
                DescribeInstancesResponse describeResponse = ec2Client.describeInstances(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build()
                );
                String privateIp = describeResponse.reservations().get(0).instances().get(0).privateIpAddress();
                log.info("Instance {} is running with IP {}", instanceId, privateIp);

                return new Seedling(
                    seedling.id(),
                    seedling.groveId(),
                    instanceId,
                    privateIp,
                    22,
                    SeedlingState.SAPLING,
                    seedling.spec(),
                    seedling.plantedAt(),
                    Instant.now()
                );

            } catch (Exception e) {
                log.error("Failed to plant seedling {} on EC2", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Seedling> water(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String instanceId = seedling.providerInstanceId();
                log.info("Watering (starting) EC2 instance {} for seedling {}", instanceId, seedling.id());

                ec2Client.startInstances(
                    StartInstancesRequest.builder().instanceIds(instanceId).build()
                );

                ec2Client.waiter().waitUntilInstanceRunning(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build()
                );

                // Refresh IP (may change after stop/start)
                DescribeInstancesResponse describeResponse = ec2Client.describeInstances(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build()
                );
                String privateIp = describeResponse.reservations().get(0).instances().get(0).privateIpAddress();

                return new Seedling(
                    seedling.id(),
                    seedling.groveId(),
                    instanceId,
                    privateIp,
                    22,
                    SeedlingState.SAPLING,
                    seedling.spec(),
                    seedling.plantedAt(),
                    Instant.now()
                );

            } catch (Exception e) {
                log.error("Failed to water seedling {} on EC2", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Seedling> dormant(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String instanceId = seedling.providerInstanceId();
                log.info("Setting seedling {} (instance {}) to dormant (stopping)", seedling.id(), instanceId);

                ec2Client.stopInstances(
                    StopInstancesRequest.builder().instanceIds(instanceId).build()
                );

                ec2Client.waiter().waitUntilInstanceStopped(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build()
                );

                return seedling.withState(SeedlingState.WILTING);

            } catch (Exception e) {
                log.error("Failed to set seedling {} dormant on EC2", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> uproot(Seedling seedling) {
        return CompletableFuture.runAsync(() -> {
            try {
                String instanceId = seedling.providerInstanceId();
                log.info("Uprooting seedling {} (terminating instance {})", seedling.id(), instanceId);

                ec2Client.terminateInstances(
                    TerminateInstancesRequest.builder().instanceIds(instanceId).build()
                );

                ec2Client.waiter().waitUntilInstanceTerminated(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build()
                );

                log.info("EC2 instance {} terminated for seedling {}", instanceId, seedling.id());

            } catch (Exception e) {
                log.error("Failed to uproot seedling {} on EC2", seedling.id(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Seedling> inspect(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String instanceId = seedling.providerInstanceId();
                DescribeInstancesResponse response = ec2Client.describeInstances(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build()
                );

                if (response.reservations().isEmpty() || response.reservations().get(0).instances().isEmpty()) {
                    return seedling.withState(SeedlingState.WITHERED);
                }

                Instance instance = response.reservations().get(0).instances().get(0);
                SeedlingState state = mapEc2State(instance.state().name());

                return new Seedling(
                    seedling.id(),
                    seedling.groveId(),
                    instanceId,
                    instance.privateIpAddress(),
                    22,
                    state,
                    seedling.spec(),
                    seedling.plantedAt(),
                    seedling.readyAt()
                );

            } catch (Exception e) {
                log.error("Failed to inspect seedling {} on EC2", seedling.id(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public boolean isAvailable() {
        try {
            ec2Client.describeInstances(
                DescribeInstancesRequest.builder()
                    .maxResults(5)
                    .build()
            );
            return true;
        } catch (Exception e) {
            log.warn("AWS EC2 provider is not available: {}", e.getMessage());
            return false;
        }
    }

    private SeedlingState mapEc2State(InstanceStateName ec2State) {
        return switch (ec2State) {
            case PENDING -> SeedlingState.SPROUTING;
            case RUNNING -> SeedlingState.SAPLING;
            case STOPPING, STOPPED -> SeedlingState.WILTING;
            case SHUTTING_DOWN, TERMINATED -> SeedlingState.WITHERED;
            default -> SeedlingState.BLIGHTED;
        };
    }

    private String buildCloudInitUserData(Seedling seedling) {
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
}
