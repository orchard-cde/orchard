package dev.orchard.nursery.aws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsRequest;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.StartInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import java.util.List;

/**
 * Production {@link Ec2Operations} implementation backed by an AWS SDK v2
 * {@link Ec2Client}.
 */
public class DefaultEc2Operations implements Ec2Operations {

    private static final Logger log = LoggerFactory.getLogger(DefaultEc2Operations.class);
    private static final String NOT_FOUND_CODE = "InvalidInstanceID.NotFound";
    private static final String INCORRECT_STATE_CODE = "IncorrectInstanceState";

    private final Ec2Client client;

    public DefaultEc2Operations(Ec2Client client) {
        this.client = client;
    }

    @Override
    public String runInstance(RunInstanceParams params) {
        List<Tag> tags = params.tags().entrySet().stream()
            .map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build())
            .toList();

        RunInstancesRequest request = RunInstancesRequest.builder()
            .imageId(params.amiId())
            .instanceType(InstanceType.fromValue(params.instanceType()))
            .keyName(params.keyPairName())
            .securityGroupIds(params.securityGroupId())
            .subnetId(params.subnetId())
            .userData(params.userDataBase64())
            .minCount(1)
            .maxCount(1)
            .tagSpecifications(TagSpecification.builder()
                .resourceType(ResourceType.INSTANCE)
                .tags(tags)
                .build())
            .build();

        RunInstancesResponse response = client.runInstances(request);
        return response.instances().get(0).instanceId();
    }

    @Override
    public InstanceDescription describeInstance(String instanceId) {
        try {
            DescribeInstancesResponse response = client.describeInstances(
                DescribeInstancesRequest.builder().instanceIds(instanceId).build());

            Instance instance = response.reservations().stream()
                .flatMap(r -> r.instances().stream())
                .findFirst()
                .orElseThrow(() -> new InstanceNotFoundException(instanceId));

            return new InstanceDescription(
                instance.instanceId(),
                AwsInstanceState.fromSdkName(instance.state().nameAsString()),
                instance.publicIpAddress(),
                instance.privateIpAddress()
            );
        } catch (AwsServiceException e) {
            if (isErrorCode(e, NOT_FOUND_CODE)) {
                throw new InstanceNotFoundException(instanceId, e);
            }
            throw e;
        }
    }

    @Override
    public void terminateInstance(String instanceId) {
        try {
            client.terminateInstances(TerminateInstancesRequest.builder()
                .instanceIds(instanceId)
                .build());
        } catch (AwsServiceException e) {
            if (isErrorCode(e, NOT_FOUND_CODE)) {
                log.info("Instance {} already terminated; treating as success", instanceId);
                return;
            }
            throw e;
        }
    }

    @Override
    public void startInstance(String instanceId) {
        try {
            client.startInstances(StartInstancesRequest.builder()
                .instanceIds(instanceId)
                .build());
        } catch (AwsServiceException e) {
            if (isErrorCode(e, INCORRECT_STATE_CODE)) {
                log.info("Instance {} already running or pending; treating as success", instanceId);
                return;
            }
            throw e;
        }
    }

    @Override
    public boolean canReachApi() {
        try {
            client.describeRegions(DescribeRegionsRequest.builder().build());
            return true;
        } catch (Exception e) {
            log.debug("EC2 API unreachable: {}", e.getMessage());
            return false;
        }
    }

    private static boolean isErrorCode(AwsServiceException e, String code) {
        return e.awsErrorDetails() != null && code.equals(e.awsErrorDetails().errorCode());
    }
}
