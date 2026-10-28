package dev.orchard.nursery.aws;

import dev.orchard.nursery.aws.Ec2Operations.AwsInstanceState;
import dev.orchard.vine.SshCommandBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Polls EC2 instance state until {@code RUNNING}, then waits for SSH to become reachable.
 *
 * <p>The SSH probe is injected so unit tests and the LocalStack integration test can
 * substitute a no-op (the real shell-out is in {@link #shellSshProbe(Path)} and used by
 * the production constructor wired in {@code NurseryConfig}).
 */
public class Ec2InstanceWaiter {

    private static final Logger log = LoggerFactory.getLogger(Ec2InstanceWaiter.class);

    /** Lambda used to verify SSH auth-level readiness, not just TCP port openness. */
    @FunctionalInterface
    public interface SshProbe {
        boolean canSsh(String host, int port);
    }

    /** Thrown when a wait operation exceeds its configured timeout. */
    public static class WaitTimeoutException extends RuntimeException {
        public WaitTimeoutException(String message) { super(message); }
    }

    private final Ec2Operations operations;
    private final Duration runningTimeout;
    private final Duration runningPollInterval;
    private final Duration sshTimeout;
    private final Duration sshPollInterval;
    private final SshProbe sshProbe;

    public Ec2InstanceWaiter(
            Ec2Operations operations,
            Duration runningTimeout,
            Duration runningPollInterval,
            Duration sshTimeout,
            Duration sshPollInterval,
            SshProbe sshProbe) {
        this.operations = operations;
        this.runningTimeout = runningTimeout;
        this.runningPollInterval = runningPollInterval;
        this.sshTimeout = sshTimeout;
        this.sshPollInterval = sshPollInterval;
        this.sshProbe = sshProbe;
    }

    /** Polls {@code describeInstance} until the instance is {@link AwsInstanceState#RUNNING}. */
    public void awaitRunning(String instanceId) {
        awaitState(instanceId, AwsInstanceState.RUNNING, EnumSet.of(AwsInstanceState.PENDING));
    }

    /** Polls {@code describeInstance} until the instance is {@link AwsInstanceState#STOPPED}. */
    public void awaitStopped(String instanceId) {
        // DescribeInstances is eventually consistent: right after StopInstances it can still report RUNNING.
        awaitState(instanceId, AwsInstanceState.STOPPED, EnumSet.of(
            AwsInstanceState.STOPPING, AwsInstanceState.RUNNING, AwsInstanceState.PENDING,
            AwsInstanceState.UNKNOWN));
    }

    private void awaitState(String instanceId, AwsInstanceState target, Set<AwsInstanceState> pollingStates) {
        log.info("Waiting for EC2 instance {} to enter {} state", instanceId, target);
        Instant deadline = Instant.now().plus(runningTimeout);
        while (Instant.now().isBefore(deadline)) {
            AwsInstanceState state = operations.describeInstance(instanceId).state();
            if (state == target) {
                log.info("EC2 instance {} is {}", instanceId, target);
                return;
            }
            if (!pollingStates.contains(state)) {
                throw new IllegalStateException(
                    "Instance " + instanceId + " entered terminal state "
                        + state + " while waiting for " + target);
            }
            sleepQuietly(runningPollInterval);
        }
        throw new WaitTimeoutException(
            "Timeout waiting for EC2 instance " + instanceId + " to become "
                + target.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Waits until {@code host:port} is reachable AND the {@link SshProbe} reports success.
     * Two phases mirror {@code DefaultQemuCommands.awaitReachable}: TCP port openness, then
     * authentication-level handshake.
     */
    public void awaitSshReady(String host, int port) {
        Instant deadline = Instant.now().plus(sshTimeout);

        // Phase 1: TCP port open
        log.info("Waiting for SSH port {}:{} to open", host, port);
        boolean portOpen = false;
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1000);
                portOpen = true;
                break;
            } catch (IOException e) {
                sleepQuietly(sshPollInterval);
            }
        }
        if (!portOpen) {
            throw new WaitTimeoutException("Timeout waiting for SSH port at " + host + ":" + port);
        }

        // Phase 2: SSH auth handshake
        log.info("SSH port open at {}:{}, waiting for SSH auth", host, port);
        while (Instant.now().isBefore(deadline)) {
            if (sshProbe.canSsh(host, port)) {
                log.info("SSH ready at {}:{}", host, port);
                return;
            }
            sleepQuietly(sshPollInterval);
        }
        throw new WaitTimeoutException("Timeout waiting for SSH auth at " + host + ":" + port);
    }

    /**
     * Production SSH probe: shells out to {@code ssh} the same way
     * {@code DefaultQemuCommands} does. Returns true if {@code ssh ... echo ready}
     * exits 0.
     */
    public static SshProbe shellSshProbe(Path privateKeyPath) {
        return (host, port) -> {
            try {
                List<String> cmd = new SshCommandBuilder()
                    .host(host)
                    .port(port)
                    .identityKey(privateKeyPath)
                    .connectTimeoutSeconds(5)
                    .batchMode(true)
                    .remoteCommand("echo ready")
                    .build();

                ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
                Process p = pb.start();
                return p.waitFor() == 0;
            } catch (Exception e) {
                return false;
            }
        };
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting", e);
        }
    }
}
