package dev.orchard.nursery.aws;

import dev.orchard.nursery.aws.Ec2Operations.AwsInstanceState;
import dev.orchard.nursery.aws.Ec2Operations.InstanceDescription;
import dev.orchard.nursery.aws.Ec2Operations.InstanceNotFoundException;
import dev.orchard.nursery.aws.Ec2Operations.RunInstanceParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceState;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.StartInstancesRequest;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultEc2OperationsTest {

    @Mock Ec2Client client;

    private DefaultEc2Operations ops;

    @BeforeEach
    void setUp() {
        ops = new DefaultEc2Operations(client);
    }

    @Test
    void runInstance_passesAllFieldsToRequest() {
        when(client.runInstances(any(RunInstancesRequest.class))).thenReturn(
            RunInstancesResponse.builder()
                .instances(Instance.builder().instanceId("i-abc123").build())
                .build());

        RunInstanceParams params = new RunInstanceParams(
            "ami-1", "t3.small", "key-1", "sg-1", "subnet-1", "BASE64DATA",
            Map.of("Name", "orchard-x", "orchard:grove-id", "g1"));

        String id = ops.runInstance(params);

        assertThat(id).isEqualTo("i-abc123");

        ArgumentCaptor<RunInstancesRequest> captor = ArgumentCaptor.forClass(RunInstancesRequest.class);
        verify(client).runInstances(captor.capture());
        RunInstancesRequest req = captor.getValue();
        assertThat(req.imageId()).isEqualTo("ami-1");
        assertThat(req.instanceType().toString()).isEqualTo("t3.small");
        assertThat(req.keyName()).isEqualTo("key-1");
        assertThat(req.securityGroupIds()).containsExactly("sg-1");
        assertThat(req.subnetId()).isEqualTo("subnet-1");
        assertThat(req.userData()).isEqualTo("BASE64DATA");
        assertThat(req.minCount()).isEqualTo(1);
        assertThat(req.maxCount()).isEqualTo(1);
        assertThat(req.tagSpecifications()).hasSize(1);
        assertThat(req.tagSpecifications().get(0).tags())
            .extracting(t -> t.key() + "=" + t.value())
            .containsExactlyInAnyOrder("Name=orchard-x", "orchard:grove-id=g1");
    }

    @Test
    void describeInstance_mapsRunningWithPublicAndPrivateIp() {
        when(client.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(
            DescribeInstancesResponse.builder()
                .reservations(Reservation.builder()
                    .instances(Instance.builder()
                        .instanceId("i-abc")
                        .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                        .publicIpAddress("1.2.3.4")
                        .privateIpAddress("10.0.0.4")
                        .build())
                    .build())
                .build());

        InstanceDescription desc = ops.describeInstance("i-abc");

        assertThat(desc.instanceId()).isEqualTo("i-abc");
        assertThat(desc.state()).isEqualTo(AwsInstanceState.RUNNING);
        assertThat(desc.publicIp()).isEqualTo("1.2.3.4");
        assertThat(desc.privateIp()).isEqualTo("10.0.0.4");
    }

    @Test
    void describeInstance_notFound_throwsInstanceNotFoundException() {
        AwsServiceException notFound = (AwsServiceException) Ec2Exception.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidInstanceID.NotFound").build())
            .message("instance gone")
            .build();
        when(client.describeInstances(any(DescribeInstancesRequest.class))).thenThrow(notFound);

        assertThatThrownBy(() -> ops.describeInstance("i-missing"))
            .isInstanceOf(InstanceNotFoundException.class)
            .hasMessageContaining("i-missing");
    }

    @Test
    void describeInstance_emptyReservations_throwsInstanceNotFoundException() {
        when(client.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(
            DescribeInstancesResponse.builder().reservations(List.of()).build());

        assertThatThrownBy(() -> ops.describeInstance("i-empty"))
            .isInstanceOf(InstanceNotFoundException.class);
    }

    @Test
    void describeInstance_nonNotFoundAwsError_propagates() {
        AwsServiceException authFailure = (AwsServiceException) Ec2Exception.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("AuthFailure").build())
            .message("not authorized")
            .build();
        when(client.describeInstances(any(DescribeInstancesRequest.class))).thenThrow(authFailure);

        assertThatThrownBy(() -> ops.describeInstance("i-anything"))
            .isInstanceOf(AwsServiceException.class)
            .isNotInstanceOf(InstanceNotFoundException.class)
            .hasMessageContaining("not authorized");
    }

    @Test
    void terminateInstance_sendsCorrectRequest() {
        ops.terminateInstance("i-abc");

        ArgumentCaptor<TerminateInstancesRequest> captor =
            ArgumentCaptor.forClass(TerminateInstancesRequest.class);
        verify(client).terminateInstances(captor.capture());
        assertThat(captor.getValue().instanceIds()).containsExactly("i-abc");
    }

    @Test
    void terminateInstance_swallowsInvalidInstanceIdNotFound() {
        AwsServiceException notFound = (AwsServiceException) Ec2Exception.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidInstanceID.NotFound").build())
            .message("instance gone")
            .build();
        when(client.terminateInstances(any(TerminateInstancesRequest.class))).thenThrow(notFound);

        // Must not throw
        ops.terminateInstance("i-gone");

        verify(client).terminateInstances(any(TerminateInstancesRequest.class));
    }

    @Test
    void startInstance_sendsCorrectRequest() {
        ops.startInstance("i-abc");

        ArgumentCaptor<StartInstancesRequest> captor =
            ArgumentCaptor.forClass(StartInstancesRequest.class);
        verify(client).startInstances(captor.capture());
        assertThat(captor.getValue().instanceIds()).containsExactly("i-abc");
    }

    @Test
    void startInstance_nonIncorrectStateAwsError_propagates() {
        AwsServiceException authFailure = (AwsServiceException) Ec2Exception.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("AuthFailure").build())
            .message("not authorized")
            .build();
        when(client.startInstances(any(StartInstancesRequest.class))).thenThrow(authFailure);

        assertThatThrownBy(() -> ops.startInstance("i-anything"))
            .isInstanceOf(AwsServiceException.class)
            .hasMessageContaining("not authorized");
    }

    @Test
    void startInstance_swallowsIncorrectInstanceState() {
        AwsServiceException already = (AwsServiceException) Ec2Exception.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("IncorrectInstanceState").build())
            .message("already running")
            .build();
        when(client.startInstances(any(StartInstancesRequest.class))).thenThrow(already);

        // Must not throw
        ops.startInstance("i-running");

        verify(client).startInstances(any(StartInstancesRequest.class));
    }

    @Test
    void canReachApi_true_whenDescribeRegionsSucceeds() {
        when(client.describeRegions(any(DescribeRegionsRequest.class))).thenReturn(
            DescribeRegionsResponse.builder().build());

        assertThat(ops.canReachApi()).isTrue();
    }

    @Test
    void canReachApi_false_whenDescribeRegionsThrows() {
        when(client.describeRegions(any(DescribeRegionsRequest.class)))
            .thenThrow(new RuntimeException("network down"));

        assertThat(ops.canReachApi()).isFalse();
    }
}
