package dev.orchard.nursery.aws;

import dev.orchard.core.model.Plot;
import dev.orchard.core.model.ProviderType;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmEndpoint;
import dev.orchard.core.model.Host;
import dev.orchard.core.model.ProviderHandle;
import dev.orchard.core.model.VmPlot;
import dev.orchard.nursery.AbstractVmGroveProvider;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.nursery.aws.Ec2Operations.InstanceDescription;
import dev.orchard.nursery.aws.Ec2Operations.InstanceNotFoundException;
import dev.orchard.nursery.aws.Ec2Operations.RunInstanceParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

/**
 * AWS EC2 implementation of {@link GroveProvider}.
 *
 * <p>Provisions EC2 instances using cloud-init for initial bootstrap. Each method
 * runs on a virtual-thread executor matching {@code QemuGroveProvider}'s pattern.
 *
 * <p><b>Failure semantics:</b> on any exception during {@link #plantSubstrate(Plot)},
 * the plot moves to {@link PlotState#BLIGHTED} and the instance is
 * <i>not</i> terminated (matches QEMU's leak-and-return-BLIGHTED behavior;
 * operators are expected to clean up via the AWS console).
 */
public class Ec2GroveProvider extends AbstractVmGroveProvider<String> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Ec2GroveProvider.class);
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
    public ProviderType type() {
        return ProviderType.AWS_EC2;
    }

    @Override
    protected String launch(VmPlot plot) {
        String publicKey = readPublicKey();

        String userDataBase64 = Ec2UserData.renderBase64(
            publicKey, plot.authorizedKeys(), devcontainerCliConfig.version());
        String instanceType = config.resolveInstanceType(plot.spec().cpuCores());

        Map<String, String> tags = Map.of(
            "Name", "orchard-" + plot.id(),
            "orchard:grove-id", plot.groveId().toString(),
            "orchard:seedling-id", plot.id().toString()
        );

        RunInstanceParams params = new RunInstanceParams(
            config.amiId(), instanceType, config.keyPairName(),
            config.securityGroupId(), config.subnetId(), userDataBase64, tags);

        String instanceId = operations.runInstance(params);
        log.info("Launched EC2 instance {} for plot {}", instanceId, plot.id());
        return instanceId;
    }

    @Override
    protected void awaitRunning(VmPlot plot, String instanceId) {
        waiter.awaitRunning(instanceId);
    }

    @Override
    protected VmEndpoint resolveEndpoint(VmPlot plot, String instanceId) {
        InstanceDescription desc = operations.describeInstance(instanceId);
        return new VmEndpoint(new ProviderHandle(instanceId), new Host(selectIp(desc)), SSH_PORT);
    }

    @Override
    protected void awaitReachable(VmPlot plot, VmEndpoint endpoint) {
        waiter.awaitSshReady(endpoint.host().value(), endpoint.sshPort());
    }

    @Override
    protected String failureContext(String instanceId) {
        return instanceId == null ? "" : " (instance=" + instanceId + ")";
    }

    @Override
    public CompletableFuture<Plot> water(Plot plot) {
        VmPlot vm = narrow(plot);
        return CompletableFuture.supplyAsync(() -> {
            try {
                operations.startInstance(vm.providerInstanceId());
                waiter.awaitRunning(vm.providerInstanceId());
                InstanceDescription desc = operations.describeInstance(vm.providerInstanceId());
                return vm.withEndpoint(new VmEndpoint(
                        new ProviderHandle(vm.providerInstanceId()), new Host(selectIp(desc)), vm.sshPort()))
                    .withState(PlotState.ROOTED);
            // InterruptedException paths are wrapped by Ec2InstanceWaiter.sleepQuietly
            // and surface as RuntimeException, caught below.
            } catch (Exception e) {
                log.error("Failed to water plot {} (instance={}): {}",
                    vm.id(), vm.providerInstanceId(), e.getMessage(), e);
                return vm.withState(PlotState.BLIGHTED);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Plot> dormant(Plot plot) {
        VmPlot vm = narrow(plot);
        // TODO: implement real StopInstances (matches current QemuGroveProvider stub).
        log.info("dormant() requested for plot {} — returning WILTING without calling AWS", vm.id());
        return CompletableFuture.completedFuture(vm.withState(PlotState.WILTING));
    }

    @Override
    public CompletableFuture<Void> uproot(Plot plot) {
        VmPlot vm = narrow(plot);
        return CompletableFuture.supplyAsync(() -> {
            try {
                operations.terminateInstance(vm.providerInstanceId());
                log.info("Uprooted plot {} (instance={})",
                    vm.id(), vm.providerInstanceId());
            } catch (Exception e) {
                log.error("Failed to uproot plot {} (instance={}): {}",
                    vm.id(), vm.providerInstanceId(), e.getMessage(), e);
                throw new CompletionException(e);
            }
            return (Void) null;
        }, executor);
    }

    @Override
    public CompletableFuture<Plot> inspect(Plot plot) {
        VmPlot vm = narrow(plot);
        return CompletableFuture.supplyAsync(() -> {
            try {
                InstanceDescription desc = operations.describeInstance(vm.providerInstanceId());
                PlotState mapped = switch (desc.state()) {
                    case RUNNING -> PlotState.ROOTED;
                    case STOPPING, SHUTTING_DOWN -> PlotState.WILTING;
                    case STOPPED -> PlotState.DORMANT;
                    case TERMINATED -> PlotState.WITHERED;
                    case PENDING -> PlotState.SPROUTING;
                    case UNKNOWN -> vm.state();
                };
                return vm.withState(mapped);
            } catch (InstanceNotFoundException e) {
                return vm.withState(PlotState.WITHERED);
            } catch (Exception e) {
                log.warn("Failed to inspect plot {} (instance={}): {}",
                    vm.id(), vm.providerInstanceId(), e.getMessage());
                return vm;
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
