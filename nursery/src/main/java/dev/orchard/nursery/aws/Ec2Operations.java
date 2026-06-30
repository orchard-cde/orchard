package dev.orchard.nursery.aws;

import java.util.Map;

/**
 * Thin abstraction over the AWS SDK EC2 client. Implementations translate
 * orchard-shaped requests/responses to and from SDK types, allowing the
 * provider and waiter to be unit-tested without mocking the full SDK
 * fluent-builder surface.
 */
public interface Ec2Operations {

    /**
     * Launches a single EC2 instance.
     * @return the instance id of the launched instance (e.g. {@code i-0abc...})
     * @throws RuntimeException (typically wrapping an AWS SDK exception) if the
     *     instance could not be launched — common causes: insufficient capacity,
     *     IAM permission denied, invalid subnet, or AMI/region mismatch.
     */
    String runInstance(RunInstanceParams params);

    /**
     * Describes a single EC2 instance.
     * @throws InstanceNotFoundException if the instance has been terminated long enough
     *     to be evicted from AWS bookkeeping (returns AWS error code
     *     {@code InvalidInstanceID.NotFound}).
     */
    InstanceDescription describeInstance(String instanceId);

    /**
     * Terminates an EC2 instance. Implementations swallow
     * {@code InvalidInstanceID.NotFound} — uprooting an already-gone instance is success.
     */
    void terminateInstance(String instanceId);

    /**
     * Starts a stopped EC2 instance. Implementations should swallow
     * {@code IncorrectInstanceState} errors when the instance is already running
     * or pending — making this idempotent for concurrent {@code water} / {@code plant} calls.
     */
    void startInstance(String instanceId);

    /**
     * Returns true iff the EC2 API is reachable from this process with the
     * configured region and credentials. Implementations should perform a
     * minimal request (e.g. {@code DescribeRegions}) and return false on any
     * exception. Used by {@link Ec2SeedlingProvider#isAvailable()}.
     */
    boolean canReachApi();

    /**
     * Parameters for {@link #runInstance(RunInstanceParams)}.
     * <p>The provider populates {@code tags} with at least:
     * {@code Name=orchard-<seedlingId>}, {@code orchard:grove-id=<groveId>},
     * {@code orchard:seedling-id=<seedlingId>}.
     */
    record RunInstanceParams(
        String amiId,
        String instanceType,
        String keyPairName,
        String securityGroupId,
        String subnetId,
        String userDataBase64,
        Map<String, String> tags
    ) {}

    /**
     * A snapshot of an EC2 instance.
     * <p>{@code publicIp} and {@code privateIp} may be null.
     */
    record InstanceDescription(
        String instanceId,
        AwsInstanceState state,
        String publicIp,
        String privateIp
    ) {}

    /**
     * Subset of EC2 instance states that the orchard provider needs.
     * Maps directly to {@code software.amazon.awssdk.services.ec2.model.InstanceStateName}.
     */
    enum AwsInstanceState {
        PENDING,
        RUNNING,
        SHUTTING_DOWN,
        STOPPING,
        STOPPED,
        TERMINATED,
        /** AWS reported a state we don't recognize. Consumers should fail fast rather than poll. */
        UNKNOWN;

        /**
         * Maps an SDK state-name string (e.g. {@code "running"}, {@code "shutting-down"})
         * to a value here. Null or unrecognized values map to {@link #UNKNOWN} so the
         * caller can fail fast — mapping unknowns to {@code PENDING} would cause the
         * instance waiter to poll forever on a state that will never resolve.
         */
        public static AwsInstanceState fromSdkName(String name) {
            if (name == null) {
                return UNKNOWN;
            }
            return switch (name) {
                case "pending" -> PENDING;
                case "running" -> RUNNING;
                case "shutting-down" -> SHUTTING_DOWN;
                case "stopping" -> STOPPING;
                case "stopped" -> STOPPED;
                case "terminated" -> TERMINATED;
                default -> UNKNOWN;
            };
        }
    }

    /**
     * Thrown when a requested instance is not found (AWS error code
     * {@code InvalidInstanceID.NotFound}).
     */
    class InstanceNotFoundException extends RuntimeException {
        public InstanceNotFoundException(String instanceId) {
            super("EC2 instance not found: " + instanceId);
        }
        public InstanceNotFoundException(String instanceId, Throwable cause) {
            super("EC2 instance not found: " + instanceId, cause);
        }
    }
}
