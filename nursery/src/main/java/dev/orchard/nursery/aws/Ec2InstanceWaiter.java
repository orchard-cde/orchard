package dev.orchard.nursery.aws;

import dev.orchard.nursery.aws.Ec2Operations.AwsInstanceState;
import dev.orchard.nursery.aws.Ec2Operations.InstanceDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
        log.info("Waiting for EC2 instance {} to enter RUNNING state", instanceId);
        Instant deadline = Instant.now().plus(runningTimeout);
        while (Instant.now().isBefore(deadline)) {
            InstanceDescription desc = operations.describeInstance(instanceId);
            switch (desc.state()) {
                case RUNNING -> {
                    log.info("EC2 instance {} is RUNNING", instanceId);
                    return;
                }
                case PENDING -> sleepQuietly(runningPollInterval);
                case TERMINATED, SHUTTING_DOWN, STOPPED, STOPPING, UNKNOWN ->
                    throw new IllegalStateException(
                        "Instance " + instanceId + " entered terminal state "
                            + desc.state() + " while waiting for RUNNING");
            }
        }
        throw new WaitTimeoutException(
            "Timeout waiting for EC2 instance " + instanceId + " to become running");
    }

    /**
     * Waits until {@code host:port} is reachable AND the {@link SshProbe} reports success.
     * Two phases mirror {@code QemuSeedlingProvider.waitForSsh}: TCP port openness, then
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
     * {@code QemuSeedlingProvider} does. Returns true if {@code ssh ... echo ready}
     * exits 0.
     */
    public static SshProbe shellSshProbe(Path privateKeyPath) {
        return (host, port) -> {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add("ssh");
                cmd.add("-o"); cmd.add("StrictHostKeyChecking=no");
                cmd.add("-o"); cmd.add("UserKnownHostsFile=/dev/null");
                cmd.add("-o"); cmd.add("ConnectTimeout=5");
                cmd.add("-o"); cmd.add("BatchMode=yes");
                if (privateKeyPath != null && Files.exists(privateKeyPath)) {
                    cmd.add("-i"); cmd.add(privateKeyPath.toString());
                }
                cmd.add("-p"); cmd.add(String.valueOf(port));
                cmd.add("cultivator@" + host);
                cmd.add("echo ready");

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
