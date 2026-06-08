package dev.orchard.nursery.aws;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.Seedling.SeedlingSpec;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.nursery.aws.Ec2Operations.AwsInstanceState;
import dev.orchard.nursery.aws.Ec2Operations.InstanceDescription;
import dev.orchard.nursery.aws.Ec2Operations.InstanceNotFoundException;
import dev.orchard.nursery.aws.Ec2Operations.RunInstanceParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Ec2SeedlingProviderTest {

    @Mock Ec2Operations ops;
    @Mock Ec2InstanceWaiter waiter;

    @TempDir Path tempDir;

    private Path keyPath;
    private Seedling germinated;

    @BeforeEach
    void setUp() throws Exception {
        keyPath = tempDir.resolve("orchard_test_ed25519");
        Files.writeString(keyPath, "private-fake");
        Files.writeString(Path.of(keyPath + ".pub"), "ssh-ed25519 AAAA testkey orchard@test");

        germinated = Seedling.germinate(UUID.randomUUID(), SeedlingSpec.small());
    }

    private Ec2Config configWith(Ec2Config.IpMode mode) {
        return new Ec2Config(
            "us-east-1", "ami-1", "key-1", "sg-1", "subnet-1",
            Map.of(2, "t3.small"),
            mode,
            keyPath
        );
    }

    private Ec2SeedlingProvider providerWith(Ec2Config config) {
        return new Ec2SeedlingProvider(config, ops, waiter);
    }

    @Test
    void getProviderId_returnsAwsEc2() {
        assertThat(providerWith(configWith(Ec2Config.IpMode.AUTO)).getProviderId())
            .isEqualTo("aws-ec2");
    }

    @Test
    void plant_happyPath_returnsSaplingWithProviderDetails() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "1.2.3.4", "10.0.0.4"));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plant(germinated).join();

        assertThat(result.state()).isEqualTo(SeedlingState.SAPLING);
        assertThat(result.providerInstanceId()).isEqualTo("i-abc");
        assertThat(result.ipAddress()).isEqualTo("1.2.3.4");  // AUTO prefers public
        assertThat(result.sshPort()).isEqualTo(22);
        verify(waiter).awaitRunning("i-abc");
        verify(waiter).awaitSshReady("1.2.3.4", 22);
    }

    @Test
    void plant_runInstanceThrows_returnsBlightedAndDoesNotTerminate() {
        when(ops.runInstance(any(RunInstanceParams.class)))
            .thenThrow(new RuntimeException("auth failed"));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plant(germinated).join();

        assertThat(result.state()).isEqualTo(SeedlingState.BLIGHTED);
        verify(ops, never()).terminateInstance(anyString());
    }

    @Test
    void plant_waitRunningTimesOut_returnsBlightedAndDoesNotTerminate() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        doThrow(new Ec2InstanceWaiter.WaitTimeoutException("timeout"))
            .when(waiter).awaitRunning("i-abc");

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plant(germinated).join();

        assertThat(result.state()).isEqualTo(SeedlingState.BLIGHTED);
        verify(ops, never()).terminateInstance(anyString());
    }

    @Test
    void plant_sshTimesOut_returnsBlightedAndDoesNotTerminate() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "1.2.3.4", "10.0.0.4"));
        doThrow(new Ec2InstanceWaiter.WaitTimeoutException("ssh"))
            .when(waiter).awaitSshReady(anyString(), org.mockito.ArgumentMatchers.eq(22));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plant(germinated).join();

        assertThat(result.state()).isEqualTo(SeedlingState.BLIGHTED);
        verify(ops, never()).terminateInstance(anyString());
    }

    @Test
    void plant_ipModeAuto_prefersPublicOverPrivate() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "1.2.3.4", "10.0.0.4"));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plant(germinated).join();

        assertThat(result.ipAddress()).isEqualTo("1.2.3.4");
    }

    @Test
    void plant_ipModeAuto_fallsBackToPrivateWhenNoPublic() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, null, "10.0.0.4"));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .plant(germinated).join();

        assertThat(result.ipAddress()).isEqualTo("10.0.0.4");
    }

    @Test
    void plant_ipModePublic_blightsWhenNoPublicIp() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, null, "10.0.0.4"));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.PUBLIC))
            .plant(germinated).join();

        assertThat(result.state()).isEqualTo(SeedlingState.BLIGHTED);
    }

    @Test
    void plant_ipModePrivate_alwaysUsesPrivate() {
        when(ops.runInstance(any(RunInstanceParams.class))).thenReturn("i-abc");
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "1.2.3.4", "10.0.0.4"));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.PRIVATE))
            .plant(germinated).join();

        assertThat(result.ipAddress()).isEqualTo("10.0.0.4");
    }

    @Test
    void water_startsInstanceAndRefreshesIp() {
        Seedling planted = germinated.withProviderDetails("i-abc", "1.2.3.4")
            .withState(SeedlingState.WILTING);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "5.6.7.8", "10.0.0.4"));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .water(planted).join();

        assertThat(result.state()).isEqualTo(SeedlingState.SAPLING);
        assertThat(result.ipAddress()).isEqualTo("5.6.7.8");
        verify(ops).startInstance("i-abc");
        verify(waiter).awaitRunning("i-abc");
    }

    @Test
    void dormant_returnsWiltingWithoutTouchingAws() {
        Seedling planted = germinated.withProviderDetails("i-abc", "1.2.3.4")
            .withState(SeedlingState.SAPLING);

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .dormant(planted).join();

        assertThat(result.state()).isEqualTo(SeedlingState.WILTING);
        verifyNoInteractions(ops, waiter);
    }

    @Test
    void uproot_callsTerminateAndReturnsNull() throws Exception {
        Seedling planted = germinated.withProviderDetails("i-abc", "1.2.3.4")
            .withState(SeedlingState.SAPLING);

        Void result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .uproot(planted).join();

        assertThat(result).isNull();
        verify(ops).terminateInstance("i-abc");
    }

    @Test
    void inspect_mapsRunningToSapling() {
        Seedling planted = germinated.withProviderDetails("i-abc", "1.2.3.4")
            .withState(SeedlingState.SAPLING);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.RUNNING, "1.2.3.4", "10.0.0.4"));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        assertThat(result.state()).isEqualTo(SeedlingState.SAPLING);
    }

    @Test
    void inspect_mapsStoppedToWilting() {
        Seedling planted = germinated.withProviderDetails("i-abc", "1.2.3.4")
            .withState(SeedlingState.SAPLING);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.STOPPED, null, "10.0.0.4"));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        assertThat(result.state()).isEqualTo(SeedlingState.WILTING);
    }

    @Test
    void inspect_mapsTerminatedToWithered() {
        Seedling planted = germinated.withProviderDetails("i-abc", "1.2.3.4")
            .withState(SeedlingState.SAPLING);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.TERMINATED, null, null));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        assertThat(result.state()).isEqualTo(SeedlingState.WITHERED);
    }

    @Test
    void inspect_mapsPendingToSprouting() {
        Seedling planted = germinated.withProviderDetails("i-abc", "1.2.3.4")
            .withState(SeedlingState.SAPLING);
        when(ops.describeInstance("i-abc")).thenReturn(
            new InstanceDescription("i-abc", AwsInstanceState.PENDING, null, "10.0.0.4"));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        assertThat(result.state()).isEqualTo(SeedlingState.SPROUTING);
    }

    @Test
    void inspect_instanceNotFound_returnsWithered() {
        Seedling planted = germinated.withProviderDetails("i-gone", "1.2.3.4")
            .withState(SeedlingState.SAPLING);
        when(ops.describeInstance("i-gone")).thenThrow(new InstanceNotFoundException("i-gone"));

        Seedling result = providerWith(configWith(Ec2Config.IpMode.AUTO))
            .inspect(planted).join();

        assertThat(result.state()).isEqualTo(SeedlingState.WITHERED);
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

        assertThat(new Ec2SeedlingProvider(config, ops, waiter).isAvailable()).isFalse();
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

        assertThat(new Ec2SeedlingProvider(config, ops, waiter).isAvailable()).isFalse();
        verifyNoInteractions(ops);
    }
}
