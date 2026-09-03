package dev.orchard.nursery.aws;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.nursery.AbstractGroveProvider;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.nursery.PlantedSeedling;
import dev.orchard.nursery.aws.Ec2Operations.InstanceDescription;
import dev.orchard.nursery.aws.Ec2Operations.InstanceNotFoundException;
import dev.orchard.nursery.aws.Ec2Operations.RunInstanceParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * AWS EC2 implementation of {@link GroveProvider}.
 *
 * <p>Provisions EC2 instances using cloud-init for initial bootstrap. Each method
 * runs on a virtual-thread executor matching {@code QemuGroveProvider}'s pattern.
 *
 * <p><b>Failure semantics:</b> on any exception during {@link #plantSubstrate(Seedling)},
 * the seedling moves to {@link SeedlingState#BLIGHTED} and the instance is
 * <i>not</i> terminated (matches QEMU's leak-and-return-BLIGHTED behavior;
 * operators are expected to clean up via the AWS console).
 */
public class Ec2GroveProvider extends AbstractGroveProvider<String> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Ec2GroveProvider.class);
    private static final String PROVIDER_ID = "aws-ec2";
    private static final int SSH_PORT = 22;

    private final Ec2Config config;
    private final Ec2Operations operations;
    private final Ec2InstanceWaiter waiter;
    private final DevcontainerCliConfig devcontainerCliConfig;

    public Ec2GroveProvider(Ec2Config config, Ec2Operations operations, Ec2InstanceWaiter waiter,
                               DevcontainerCliConfig devcontainerCliConfig, FruitGrower fruitGrower) {
        super(Executors.newVirtualThreadPerTaskExecutor(), fruitGrower);
        this.config = config;
        this.operations = operations;
        this.waiter = waiter;
        this.devcontainerCliConfig = devcontainerCliConfig;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    protected String launch(Seedling seedling) {
        String publicKey = readPublicKey();

        String userDataBase64 = Ec2UserData.renderBase64(
            seedling.spec(), publicKey, seedling.authorizedKeys(), devcontainerCliConfig.version());
        String instanceType = config.resolveInstanceType(seedling.spec().cpuCores());

        Map<String, String> tags = Map.of(
            "Name", "orchard-" + seedling.id(),
            "orchard:grove-id", seedling.groveId().toString(),
            "orchard:seedling-id", seedling.id().toString()
        );

        RunInstanceParams params = new RunInstanceParams(
            config.amiId(), instanceType, config.keyPairName(),
            config.securityGroupId(), config.subnetId(), userDataBase64, tags);

        String instanceId = operations.runInstance(params);
        log.info("Launched EC2 instance {} for seedling {}", instanceId, seedling.id());
        return instanceId;
    }

    @Override
    protected void awaitRunning(Seedling seedling, String instanceId) {
        waiter.awaitRunning(instanceId);
    }

    @Override
    protected PlantedSeedling resolveEndpoint(Seedling seedling, String instanceId) {
        InstanceDescription desc = operations.describeInstance(instanceId);
        return new PlantedSeedling(instanceId, selectIp(desc), SSH_PORT);
    }

    @Override
    protected void awaitReachable(Seedling seedling, PlantedSeedling planted) {
        waiter.awaitSshReady(planted.host(), planted.sshPort());
    }

    @Override
    protected String failureContext(String instanceId) {
        return instanceId == null ? "" : " (instance=" + instanceId + ")";
    }

    @Override
    public CompletableFuture<Seedling> water(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                operations.startInstance(seedling.providerInstanceId());
                waiter.awaitRunning(seedling.providerInstanceId());
                InstanceDescription desc = operations.describeInstance(seedling.providerInstanceId());
                String ip = selectIp(desc);
                return seedling.withProviderDetails(seedling.providerInstanceId(), ip)
                    .withState(SeedlingState.SAPLING);
            // InterruptedException paths are wrapped by Ec2InstanceWaiter.sleepQuietly
            // and surface as RuntimeException, caught below.
            } catch (Exception e) {
                log.error("Failed to water seedling {} (instance={}): {}",
                    seedling.id(), seedling.providerInstanceId(), e.getMessage(), e);
                return seedling.withState(SeedlingState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Seedling> dormant(Seedling seedling) {
        // TODO: implement real StopInstances (matches current QemuGroveProvider stub).
        log.info("dormant() requested for seedling {} — returning WILTING without calling AWS",
            seedling.id());
        return CompletableFuture.completedFuture(seedling.withState(SeedlingState.WILTING));
    }

    @Override
    public CompletableFuture<Void> uproot(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                operations.terminateInstance(seedling.providerInstanceId());
                log.info("Uprooted seedling {} (instance={})",
                    seedling.id(), seedling.providerInstanceId());
            } catch (Exception e) {
                log.error("Failed to uproot seedling {} (instance={}): {}",
                    seedling.id(), seedling.providerInstanceId(), e.getMessage(), e);
            }
            return (Void) null;
        }, executor);
    }

    @Override
    public CompletableFuture<Seedling> inspect(Seedling seedling) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                InstanceDescription desc = operations.describeInstance(seedling.providerInstanceId());
                SeedlingState mapped = switch (desc.state()) {
                    case RUNNING -> SeedlingState.SAPLING;
                    case STOPPED, STOPPING -> SeedlingState.WILTING;
                    case TERMINATED, SHUTTING_DOWN -> SeedlingState.WITHERED;
                    case PENDING -> SeedlingState.SPROUTING;
                    case UNKNOWN -> seedling.state();
                };
                return seedling.withState(mapped);
            } catch (InstanceNotFoundException e) {
                return seedling.withState(SeedlingState.WITHERED);
            } catch (Exception e) {
                log.warn("Failed to inspect seedling {} (instance={}): {}",
                    seedling.id(), seedling.providerInstanceId(), e.getMessage());
                return seedling;
            }
        }, executor);
    }

    @Override
    public boolean isAvailable() {
        if (isBlank(config.region()) || isBlank(config.amiId())
            || isBlank(config.keyPairName()) || isBlank(config.securityGroupId())
            || isBlank(config.subnetId())) {
            log.warn("AWS EC2 provider unavailable: required config field is blank");
            return false;
        }
        if (config.sshKeyPath() == null) {
            log.warn("AWS EC2 provider unavailable: sshKeyPath is null");
            return false;
        }
        Path publicKeyPath = Path.of(config.sshKeyPath() + ".pub");
        if (!Files.isReadable(publicKeyPath)) {
            log.warn("AWS EC2 provider unavailable: public key not readable at {}", publicKeyPath);
            return false;
        }
        if (!operations.canReachApi()) {
            log.warn("AWS EC2 provider unavailable: EC2 API unreachable in region {}", config.region());
            return false;
        }
        return true;
    }

    /**
     * Shuts down the virtual-thread executor. Called by Spring on context shutdown
     * when the provider bean is destroyed.
     */
    @Override
    public void close() {
        log.info("Shutting down EC2 seedling provider executor");
        executor.shutdown();
    }

    private String readPublicKey() {
        try {
            return Files.readString(Path.of(config.sshKeyPath() + ".pub")).trim();
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to read SSH public key at " + config.sshKeyPath() + ".pub", e);
        }
    }

    private String selectIp(InstanceDescription desc) {
        return switch (config.ipMode()) {
            case AUTO -> desc.publicIp() != null ? desc.publicIp() : requirePrivate(desc);
            case PUBLIC -> requirePublic(desc);
            case PRIVATE -> requirePrivate(desc);
        };
    }

    private static String requirePublic(InstanceDescription desc) {
        if (desc.publicIp() == null) {
            throw new IllegalStateException(
                "ipMode=PUBLIC but instance " + desc.instanceId() + " has no public IP");
        }
        return desc.publicIp();
    }

    private static String requirePrivate(InstanceDescription desc) {
        if (desc.privateIp() == null) {
            throw new IllegalStateException(
                "Instance " + desc.instanceId() + " has no private IP");
        }
        return desc.privateIp();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
