package dev.orchard.nursery.aws;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.PlotRequest;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmEndpoint;
import dev.orchard.core.model.Host;
import dev.orchard.core.model.ProviderHandle;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.aws.Ec2Operations.AwsInstanceState;
import dev.orchard.nursery.aws.Ec2Operations.InstanceDescription;
import dev.orchard.nursery.aws.Ec2Operations.InstanceNotFoundException;
import dev.orchard.nursery.aws.Ec2Operations.RunInstanceParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Ec2GroveProviderTest {

    @Mock Ec2Operations ops;
    @Mock Ec2InstanceWaiter waiter;

    private static final DevcontainerCliConfig CLI_CONFIG = new DevcontainerCliConfig("0.87.0", 0, 0);

    @TempDir Path tempDir;

    private Path keyPath;
    private VmPlot germinated;

    @BeforeEach
    void setUp() throws Exception {
        keyPath = tempDir.resolve("orchard_test_ed25519");
        Files.writeString(keyPath, "private-fake");
        Files.writeString(Path.of(keyPath + ".pub"), "ssh-ed25519 AAAA testkey orchard@test");

        germinated = VmPlot.germinate(UUID.randomUUID(), "aws-ec2", PlotRequest.from(VmSpec.small()));
    }

    private Ec2Config configWith(Ec2Config.IpMode mode) {
        return new Ec2Config(
            "us-east-1", "ami-1", "key-1", "sg-1", "subnet-1",
            Map.of(2, "t3.small"),
            mode,
            keyPath
        );
    }

    private Ec2GroveProvider providerWith(Ec2Config config) {
        return new Ec2GroveProvider(config, ops, waiter, CLI_CONFIG, new FruitGrower());
    }

    @Test
    void getProviderId_returnsAwsEc2() {
        assertThat(providerWith(configWith(Ec2Config.IpMode.AUTO)).getProviderId())
            .isEqualTo("aws-ec2");
    }

    @Test
    void plantSubstrate_happyPath_returnsSaplingWithProviderDetails() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "1.2.3.4", "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plantSubstrate(germinated).join();

        assertThat(result.state()).isEqualTo(PlotState.SAPLING);
        assertThat(result.providerInstanceId()).isEqualTo("i-abc");
        assertThat(result.host()).isEqualTo("1.2.3.4");  // AUTO prefers public
        assertThat(result.sshPort()).isEqualTo(22);
        verify(waiter).awaitRunning("i-abc");
        verify(waiter).awaitSshReady("1.2.3.4", 22);
    }

    @Test
    void plantSubstrate_runInstanceThrows_returnsBlightedAndDoesNotTerminate() {
        when(ops.runInstance(any(RunInstanceParams.class)))
            .thenThrow(new RuntimeException("auth failed"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plantSubstrate(germinated).join();

        assertThat(result.state()).isEqualTo(PlotState.BLIGHTED);
        verify(ops, never()).terminateInstance(anyString());
    }

    @Test
    void plantSubstrate_waitRunningTimesOut_returnsBlightedAndDoesNotTerminate() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        doThrow(new Ec2InstanceWaiter.WaitTimeoutException("timeout"))
            .when(waiter).awaitRunning("i-abc");

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plantSubstrate(germinated).join();

        assertThat(result.state()).isEqualTo(PlotState.BLIGHTED);
        verify(ops, never()).terminateInstance(anyString());
    }

    @Test
    void plantSubstrate_sshTimesOut_returnsBlightedAndDoesNotTerminate() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "1.2.3.4", "10.0.0.4"));
        doThrow(new Ec2InstanceWaiter.WaitTimeoutException("ssh"))
            .when(waiter).awaitSshReady(anyString(), org.mockito.ArgumentMatchers.eq(22));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plantSubstrate(germinated).join();

        assertThat(result.state()).isEqualTo(PlotState.BLIGHTED);
        verify(ops, never()).terminateInstance(anyString());
    }

    @Test
    void plantSubstrate_ipModeAuto_prefersPublicOverPrivate() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "1.2.3.4", "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plantSubstrate(germinated).join();

        assertThat(result.host()).isEqualTo("1.2.3.4");
    }

    @Test
    void plantSubstrate_ipModeAuto_fallsBackToPrivateWhenNoPublic() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, null, "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plantSubstrate(germinated).join();

        assertThat(result.host()).isEqualTo("10.0.0.4");
    }

    @Test
    void plantSubstrate_ipModePublic_blightsWhenNoPublicIp() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, null, "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.PUBLIC))
            .plantSubstrate(germinated).join();

        assertThat(result.state()).isEqualTo(PlotState.BLIGHTED);
    }

    @Test
    void plantSubstrate_ipModePrivate_alwaysUsesPrivate() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "1.2.3.4", "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.PRIVATE))
            .plantSubstrate(germinated).join();

        assertThat(result.host()).isEqualTo("10.0.0.4");
    }

    @Test
    void water_startsInstanceAndRefreshesIp() {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.WILTING);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "5.6.7.8", "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .water(planted).join();

        assertThat(result.state()).isEqualTo(PlotState.SAPLING);
        assertThat(result.host()).isEqualTo("5.6.7.8");
        verify(ops).startInstance("i-abc");
        verify(waiter).awaitRunning("i-abc");
    }

    @Test
    void water_refreshesHostButPreservesSshPort() {
        VmPlot planted = germinated
            .withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), 2201))
            .withState(PlotState.WILTING);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "5.6.7.8", "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO)).water(planted).join();

        assertThat(result.state()).isEqualTo(PlotState.SAPLING);
        assertThat(result.host()).isEqualTo("5.6.7.8");
        assertThat(result.sshPort()).isEqualTo(2201);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"water", "dormant", "uproot", "inspect"})
    void ownedMethod_containerPlot_isRefusedOnTheCallingThread(String method) {
        Ec2GroveProvider provider = providerWith(configWith(Ec2Config.IpMode.AUTO));
        ContainerPlot container = new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), "docker-local",
            "c-1", PlotState.SAPLING, Instant.now(), null, Path.of("/tmp/ws"),
            ContainerRuntime.DOCKER, new ContainerSpec(2, 2048));
        Runnable call = switch (method) {
            case "water" -> () -> provider.water(container);
            case "dormant" -> () -> provider.dormant(container);
            case "uproot" -> () -> provider.uproot(container);
            case "inspect" -> () -> provider.inspect(container);
            default -> throw new IllegalArgumentException(method);
        };

        assertThatThrownBy(call::run)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("aws-ec2")
            .hasMessageContaining(container.id().toString());
    }

    @Test
    void dormant_returnsWiltingWithoutTouchingAws() {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.SAPLING);

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .dormant(planted).join();

        assertThat(result.state()).isEqualTo(PlotState.WILTING);
        verifyNoInteractions(ops, waiter);
    }

    @Test
    void uproot_callsTerminateAndReturnsNull() throws Exception {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.SAPLING);

        Void result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .uproot(planted).join();

        assertThat(result).isNull();
        verify(ops).terminateInstance("i-abc");
    }

    /**
     * Guards the propagation fix: a terminate failure must surface as an exceptional future so
     * tearDownAndRecord can mark the grove ORPHANED, not report a release that never happened.
     */
    @Test
    void uproot_terminateFails_completesExceptionally() {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.SAPLING);
        RuntimeException terminateFailure = new RuntimeException("AWS terminate-instances failed");
        doThrow(terminateFailure).when(ops).terminateInstance("i-abc");

        var future = providerWith(configWith(Ec2Config.IpMode.AUTO)).uproot(planted);

        assertThatThrownBy(future::join)
            .isInstanceOf(CompletionException.class)
            .hasCause(terminateFailure);
    }

    @Test
    void inspect_mapsRunningToSapling() {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.SAPLING);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "1.2.3.4", "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        assertThat(result.state()).isEqualTo(PlotState.SAPLING);
    }

    @Test
    void inspect_mapsStoppedToWilting() {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.SAPLING);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.STOPPED, null, "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        assertThat(result.state()).isEqualTo(PlotState.WILTING);
    }

    @Test
    void inspect_mapsTerminatedToWithered() {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.SAPLING);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.TERMINATED, null, null));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        assertThat(result.state()).isEqualTo(PlotState.WITHERED);
    }

    @Test
    void inspect_mapsPendingToSprouting() {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.SAPLING);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.PENDING, null, "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        assertThat(result.state()).isEqualTo(PlotState.SPROUTING);
    }

    @Test
    void inspect_mapsUnknownToCurrentState() {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.SAPLING);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.UNKNOWN, null, null));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        // UNKNOWN: preserve current state rather than mapping to a fixed lifecycle state.
        // We don't know what's happening; a later inspect should resolve to a real state.
        assertThat(result.state()).isEqualTo(PlotState.SAPLING);
    }

    @Test
    void inspect_instanceNotFound_returnsWithered() {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-gone"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.SAPLING);
        when(ops.describeInstance("i-gone")).thenThrow(new InstanceNotFoundException("i-gone"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        assertThat(result.state()).isEqualTo(PlotState.WITHERED);
    }

    @Test
    void isAvailable_trueWhenCanReachApiAndKeyExists() {
        when(ops.canReachApi()).thenReturn(true);

        assertThat(providerWith(configWith(Ec2Config.IpMode.AUTO)).isAvailable()).isTrue();
    }

    @Test
    void isAvailable_falseWhenCanReachApiFalse() {
        when(ops.canReachApi()).thenReturn(false);

        assertThat(providerWith(configWith(Ec2Config.IpMode.AUTO)).isAvailable()).isFalse();
    }

    @Test
    void isAvailable_falseWhenPublicKeyMissing() {
        Ec2Config config = new Ec2Config(
            "us-east-1", "ami-1", "key-1", "sg-1", "subnet-1",
            Map.of(2, "t3.small"),
            Ec2Config.IpMode.AUTO,
            tempDir.resolve("nonexistent-key")
        );

        assertThat(new Ec2GroveProvider(config, ops, waiter, CLI_CONFIG, new FruitGrower())
            .isAvailable()).isFalse();
        verifyNoInteractions(ops);
    }

    @Test
    void isAvailable_falseWhenSshKeyPathNull() {
        Ec2Config config = new Ec2Config(
            "us-east-1", "ami-1", "key-1", "sg-1", "subnet-1",
            Map.of(2, "t3.small"),
            Ec2Config.IpMode.AUTO,
            (Path) null  // explicitly null sshKeyPath
        );

        assertThat(new Ec2GroveProvider(config, ops, waiter, CLI_CONFIG, new FruitGrower())
            .isAvailable()).isFalse();
        verifyNoInteractions(ops);
    }

    @Test
    void isAvailable_falseWhenRequiredFieldBlank() {
        Ec2Config config = new Ec2Config(
            "us-east-1", "", "key-1", "sg-1", "subnet-1",
            Map.of(2, "t3.small"),
            Ec2Config.IpMode.AUTO,
            keyPath
        );

        assertThat(new Ec2GroveProvider(config, ops, waiter, CLI_CONFIG, new FruitGrower())
            .isAvailable()).isFalse();
        verifyNoInteractions(ops);
    }
}
