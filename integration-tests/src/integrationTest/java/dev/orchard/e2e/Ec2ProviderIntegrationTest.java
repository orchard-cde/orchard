package dev.orchard.e2e;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.Seedling.SeedlingSpec;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.aws.DefaultEc2Operations;
import dev.orchard.nursery.aws.Ec2Config;
import dev.orchard.nursery.aws.Ec2InstanceWaiter;
import dev.orchard.nursery.aws.Ec2Operations;
import dev.orchard.nursery.aws.Ec2SeedlingProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith(LocalStackPrerequisiteExtension.class)
class Ec2ProviderIntegrationTest {

    @Container
    static LocalStackContainer localstack =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.0"))
            .withServices(LocalStackContainer.Service.EC2);

    @TempDir
    static Path tempDir;

    // class-scoped resources — created once in @BeforeAll, torn down in @AfterAll
    private static Ec2Client rawClient;
    private static Ec2Operations ops;
    private static Ec2SeedlingProvider provider;
    private static String securityGroupId;
    private static String subnetId;

    @BeforeAll
    static void setUpAll() throws Exception {
        Path keyPath = tempDir.resolve("orchard_int_ed25519");
        Files.writeString(keyPath, "private-fake");
        Files.writeString(Path.of(keyPath + ".pub"), "ssh-ed25519 AAAA testkey orchard@int");

        rawClient = Ec2Client.builder()
            .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.EC2))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
            .region(Region.of(localstack.getRegion()))
            .httpClient(UrlConnectionHttpClient.create())
            .build();

        // LocalStack 3 validates that the security group and subnet actually exist before
        // accepting RunInstances, so we must create them up front.
        String vpcId = rawClient.createVpc(CreateVpcRequest.builder()
            .cidrBlock("10.0.0.0/16").build())
            .vpc().vpcId();

        subnetId = rawClient.createSubnet(CreateSubnetRequest.builder()
            .vpcId(vpcId).cidrBlock("10.0.1.0/24").build())
            .subnet().subnetId();

        securityGroupId = rawClient.createSecurityGroup(CreateSecurityGroupRequest.builder()
            .groupName("orchard-int-test")
            .description("Integration test security group")
            .vpcId(vpcId).build())
            .groupId();

        ops = new DefaultEc2Operations(rawClient);

        // No-op SSH probe — LocalStack doesn't actually run cloud-init or sshd.
        Ec2InstanceWaiter waiter = new Ec2InstanceWaiter(
            ops,
            Duration.ofSeconds(30), Duration.ofMillis(200),
            Duration.ofSeconds(2),  Duration.ofMillis(100),
            (host, port) -> true);

        Ec2Config config = new Ec2Config(
            localstack.getRegion(),
            // LocalStack accepts arbitrary AMI IDs as long as they look like ami-XXXXXXXX.
            "ami-12345678",
            "orchard-key",
            securityGroupId,
            subnetId,
            Map.of(2, "t3.small"),
            Ec2Config.IpMode.AUTO,
            keyPath
        );

        provider = new Ec2SeedlingProvider(config, ops, waiter, new DevcontainerCliConfig("0.87.0", 0, 0));
    }

    @AfterAll
    static void tearDownAll() {
        if (provider != null) {
            provider.close();
        }
        if (rawClient != null) {
            rawClient.close();
        }
    }

    @Test
    void provider_plant_completesWithoutUnhandledExceptionAgainstLocalStack() {
        Seedling germinated = Seedling.germinate(UUID.randomUUID(), SeedlingSpec.small());

        // LocalStack does not bind a real socket on the instance's IP, so the SSH wait
        // (phase 1: TCP port) will time out. plant() handles that by returning BLIGHTED.
        // SAPLING is acceptable if LocalStack happens to expose a reachable port; either
        // outcome means the provider survived the full call-chain without throwing.
        Seedling planted = provider.plant(germinated).join();

        assertThat(planted.state()).isIn(SeedlingState.SAPLING, SeedlingState.BLIGHTED);
    }

    @Test
    void operations_runDescribeTerminate_roundTripsViaLocalStack() {
        // Verify the SDK serialization surface end-to-end: launch, describe, terminate.
        String instanceId = ops.runInstance(new Ec2Operations.RunInstanceParams(
            "ami-87654321", "t3.small", "k", securityGroupId, subnetId, "BASE64",
            Map.of("Name", "direct-test")));

        assertThat(instanceId).startsWith("i-");

        Ec2Operations.InstanceDescription desc = ops.describeInstance(instanceId);
        assertThat(desc.instanceId()).isEqualTo(instanceId);
        assertThat(desc.state()).isIn(
            Ec2Operations.AwsInstanceState.RUNNING,
            Ec2Operations.AwsInstanceState.PENDING);

        ops.terminateInstance(instanceId);

        // LocalStack still reports the terminated instance for a window; describe should
        // either return TERMINATED/SHUTTING_DOWN or eventually throw NotFound.
        try {
            Ec2Operations.InstanceDescription after = ops.describeInstance(instanceId);
            assertThat(after.state()).isIn(
                Ec2Operations.AwsInstanceState.TERMINATED,
                Ec2Operations.AwsInstanceState.SHUTTING_DOWN);
        } catch (Ec2Operations.InstanceNotFoundException expected) {
            // also acceptable
        }
    }

    @Test
    void operations_terminateMissingInstance_isSilentlyOk() {
        // Idempotency check — uprooting an already-gone instance must not throw.
        ops.terminateInstance("i-doesnotexist0");
    }
}
