package dev.orchard.nursery.aws;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.SeedlingSpec;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmEndpoint;
import dev.orchard.core.model.Host;
import dev.orchard.core.model.ProviderHandle;
import dev.orchard.core.model.ProviderType;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
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

        germinated = VmPlot.germinate(UUID.randomUUID(), "aws-ec2", SeedlingSpec.from(VmSpec.small()));
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
    void type_isAwsEc2() {
        assertThat(providerWith(configWith(Ec2Config.IpMode.AUTO)).type()).isEqualTo(ProviderType.AWS_EC2);
    }

    @Test
    void plantSubstrate_happyPath_returnsRootedWithProviderDetails() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "1.2.3.4", "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plantSubstrate(germinated).join();

        assertThat(result.state()).isEqualTo(PlotState.ROOTED);
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

    private VmPlot dormantPlot(int sshPort) {
        return germinated
            .withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), sshPort))
            .withState(PlotState.DORMANT);
    }

    @Test
    void water_startsWaitsRefreshesIpAndAwaitsSsh_returnsRooted() {
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "5.6.7.8", "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .water(dormantPlot(germinated.sshPort())).join();

        assertThat(result.state()).isEqualTo(PlotState.ROOTED);
        assertThat(result.host()).isEqualTo("5.6.7.8");
        InOrder order = inOrder(ops, waiter);
        order.verify(ops).startInstance("i-abc");
        order.verify(waiter).awaitRunning("i-abc");
        order.verify(ops).describeInstance("i-abc");
        order.verify(waiter).awaitSshReady("5.6.7.8", 22);
    }

    @Test
    void water_refreshesHostButPreservesSshPort() {
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "5.6.7.8", "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .water(dormantPlot(2201)).join();

        assertThat(result.state()).isEqualTo(PlotState.ROOTED);
        assertThat(result.host()).isEqualTo("5.6.7.8");
        assertThat(result.sshPort()).isEqualTo(2201);
        verify(waiter).awaitSshReady("5.6.7.8", 2201);
    }

    @Test
    void water_failure_completesExceptionally() {
        doThrow(new IllegalStateException("start failed")).when(ops).startInstance("i-abc");

        assertThatThrownBy(() -> providerWith(configWith(Ec2Config.IpMode.AUTO))
            .water(dormantPlot(22)).join())
            .isInstanceOf(CompletionException.class)
            .hasRootCauseMessage("start failed");
    }

    @Test
    void water_sshNeverReady_completesExceptionally() {
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "5.6.7.8", "10.0.0.4"));
        doThrow(new Ec2InstanceWaiter.WaitTimeoutException("ssh timeout"))
            .when(waiter).awaitSshReady("5.6.7.8", 22);

        assertThatThrownBy(() -> providerWith(configWith(Ec2Config.IpMode.AUTO))
            .water(dormantPlot(22)).join())
            .isInstanceOf(CompletionException.class)
            .hasRootCauseMessage("ssh timeout");
    }

    @ParameterizedTest
    @EnumSource(value = PlotState.class, mode = EnumSource.Mode.EXCLUDE, names = "DORMANT")
    void water_notDormant_throwsIllegalState(PlotState state) {
        VmPlot plot = dormantPlot(22).withState(state);
        Ec2GroveProvider provider = providerWith(configWith(Ec2Config.IpMode.AUTO));

        assertThatThrownBy(() -> provider.water(plot))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(state.name());
        verifyNoInteractions(ops, waiter);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"water", "dormant", "uproot", "inspect"})
    void ownedMethod_containerPlot_isRefusedOnTheCallingThread(String method) {
        Ec2GroveProvider provider = providerWith(configWith(Ec2Config.IpMode.AUTO));
        ContainerPlot container = new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), "docker-local",
            "c-1", PlotState.ROOTED, Instant.now(), null, Path.of("/tmp/ws"),
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
    void dormant_stopsAndWaits_returnsDormant() {
        VmPlot planted = germinated
            .withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.WILTING);

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .dormant(planted).join();

        assertThat(result.state()).isEqualTo(PlotState.DORMANT);
        InOrder order = inOrder(ops, waiter);
        order.verify(ops).stopInstance("i-abc");
        order.verify(waiter).awaitStopped("i-abc");
    }

    @Test
    void dormant_stopFails_completesExceptionally() {
        VmPlot planted = germinated
            .withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.WILTING);
        doThrow(new IllegalStateException("stop failed")).when(ops).stopInstance("i-abc");

        assertThatThrownBy(() -> providerWith(configWith(Ec2Config.IpMode.AUTO))
            .dormant(planted).join())
            .isInstanceOf(CompletionException.class)
            .hasRootCauseMessage("stop failed");
        verify(waiter, never()).awaitStopped(anyString());
    }

    @Test
    void dormant_neverStops_completesExceptionally() {
        VmPlot planted = germinated
            .withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.WILTING);
        doThrow(new Ec2InstanceWaiter.WaitTimeoutException("stop timeout")).when(waiter).awaitStopped("i-abc");

        assertThatThrownBy(() -> providerWith(configWith(Ec2Config.IpMode.AUTO))
            .dormant(planted).join())
            .isInstanceOf(CompletionException.class)
            .hasRootCauseMessage("stop timeout");
    }

    @Test
    void uproot_callsTerminateAndReturnsNull() throws Exception {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.ROOTED);

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
            .withState(PlotState.ROOTED);
        RuntimeException terminateFailure = new RuntimeException("AWS terminate-instances failed");
        doThrow(terminateFailure).when(ops).terminateInstance("i-abc");

        var future = providerWith(configWith(Ec2Config.IpMode.AUTO)).uproot(planted);

        assertThatThrownBy(future::join)
            .isInstanceOf(CompletionException.class)
            .hasCause(terminateFailure);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"PENDING,SPROUTING", "RUNNING,ROOTED", "STOPPING,WILTING", "SHUTTING_DOWN,WILTING",
                "STOPPED,DORMANT", "TERMINATED,WITHERED"})
    void inspect_mapsAwsStateToPlotState(AwsInstanceState aws, PlotState expected) {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.ROOTED);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", aws, null, "10.0.0.4"));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        assertThat(result.state()).isEqualTo(expected);
    }

    @Test
    void inspect_mapsUnknownToCurrentState() {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.ROOTED);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.UNKNOWN, null, null));

        VmPlot result = (VmPlot) providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        // UNKNOWN: preserve current state rather than mapping to a fixed lifecycle state.
        // We don't know what's happening; a later inspect should resolve to a real state.
        assertThat(result.state()).isEqualTo(PlotState.ROOTED);
    }

    @Test
    void inspect_instanceNotFound_returnsWithered() {
        VmPlot planted = germinated.withEndpoint(new VmEndpoint(new ProviderHandle("i-gone"), new Host("1.2.3.4"), germinated.sshPort()))
            .withState(PlotState.ROOTED);
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
