package dev.orchard.nursery.aws;

import dev.orchard.nursery.aws.Ec2Operations.AwsInstanceState;
import dev.orchard.nursery.aws.Ec2Operations.InstanceDescription;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ec2InstanceWaiterTest {

    private static InstanceDescription desc(AwsInstanceState state) {
        return new InstanceDescription("i-1", state, "1.2.3.4", "10.0.0.1");
    }

    @Test
    void awaitRunning_returnsImmediatelyWhenAlreadyRunning() {
        Ec2Operations ops = new StubOps(desc(AwsInstanceState.RUNNING));
        Ec2InstanceWaiter waiter = new Ec2InstanceWaiter(
            ops, Duration.ofSeconds(1), Duration.ofMillis(10),
            Duration.ofSeconds(1), Duration.ofMillis(10),
            (host, port) -> true);

        waiter.awaitRunning("i-1");
    }

    @Test
    void awaitRunning_pollsUntilRunning() {
        StubOps ops = new StubOps(
            desc(AwsInstanceState.PENDING),
            desc(AwsInstanceState.PENDING),
            desc(AwsInstanceState.RUNNING));
        Ec2InstanceWaiter waiter = new Ec2InstanceWaiter(
            ops, Duration.ofSeconds(2), Duration.ofMillis(10),
            Duration.ofSeconds(1), Duration.ofMillis(10),
            (host, port) -> true);

        waiter.awaitRunning("i-1");

        assertThat(ops.callsMade()).isEqualTo(3);
    }

    @Test
    void awaitRunning_throwsOnTimeout() {
        Ec2Operations ops = new StubOps(desc(AwsInstanceState.PENDING));
        Ec2InstanceWaiter waiter = new Ec2InstanceWaiter(
            ops, Duration.ofMillis(50), Duration.ofMillis(10),
            Duration.ofSeconds(1), Duration.ofMillis(10),
            (host, port) -> true);

        assertThatThrownBy(() -> waiter.awaitRunning("i-1"))
            .isInstanceOf(Ec2InstanceWaiter.WaitTimeoutException.class)
            .hasMessageContaining("running");
    }

    @Test
    void awaitRunning_throwsImmediatelyOnTerminalFailureState() {
        Ec2Operations ops = new StubOps(desc(AwsInstanceState.TERMINATED));
        Ec2InstanceWaiter waiter = new Ec2InstanceWaiter(
            ops, Duration.ofSeconds(1), Duration.ofMillis(10),
            Duration.ofSeconds(1), Duration.ofMillis(10),
            (host, port) -> true);

        assertThatThrownBy(() -> waiter.awaitRunning("i-1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TERMINATED");
    }

    @Test
    void awaitRunning_throwsOnUnknownState() {
        Ec2Operations ops = new StubOps(desc(AwsInstanceState.UNKNOWN));
        Ec2InstanceWaiter waiter = new Ec2InstanceWaiter(
            ops, Duration.ofSeconds(1), Duration.ofMillis(10),
            Duration.ofSeconds(1), Duration.ofMillis(10),
            (host, port) -> true);

        assertThatThrownBy(() -> waiter.awaitRunning("i-1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UNKNOWN");
    }

    @Test
    void awaitSshReady_succeedsAgainstListeningSocket() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            Ec2InstanceWaiter waiter = new Ec2InstanceWaiter(
                new StubOps(),
                Duration.ofSeconds(1), Duration.ofMillis(10),
                Duration.ofSeconds(2), Duration.ofMillis(10),
                (host, p) -> p == port);

            waiter.awaitSshReady("127.0.0.1", port);
        }
    }

    @Test
    void awaitSshReady_timesOutWhenNothingListens() throws IOException {
        int freePort;
        try (ServerSocket scratch = new ServerSocket(0)) {
            freePort = scratch.getLocalPort();
        }

        Ec2InstanceWaiter waiter = new Ec2InstanceWaiter(
            new StubOps(),
            Duration.ofSeconds(1), Duration.ofMillis(10),
            Duration.ofMillis(100), Duration.ofMillis(10),
            (host, p) -> true);

        assertThatThrownBy(() -> waiter.awaitSshReady("127.0.0.1", freePort))
            .isInstanceOf(Ec2InstanceWaiter.WaitTimeoutException.class)
            .hasMessageContaining("SSH");
    }

    @Test
    void awaitSshReady_timesOutWhenProbeNeverSucceeds() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            Ec2InstanceWaiter waiter = new Ec2InstanceWaiter(
                new StubOps(),
                Duration.ofSeconds(1), Duration.ofMillis(10),
                Duration.ofMillis(100), Duration.ofMillis(10),
                (host, p) -> false);  // probe always fails

            assertThatThrownBy(() -> waiter.awaitSshReady("127.0.0.1", port))
                .isInstanceOf(Ec2InstanceWaiter.WaitTimeoutException.class)
                .hasMessageContaining("SSH");
        }
    }

    /** Returns each scripted description in turn; repeats the last forever. */
    private static final class StubOps implements Ec2Operations {
        private final InstanceDescription[] script;
        private final AtomicInteger idx = new AtomicInteger();

        StubOps(InstanceDescription... script) {
            this.script = script;
        }

        int callsMade() { return idx.get(); }

        @Override public InstanceDescription describeInstance(String instanceId) {
            int i = idx.getAndIncrement();
            return script[Math.min(i, script.length - 1)];
        }

        @Override public String runInstance(RunInstanceParams params) { throw new UnsupportedOperationException(); }
        @Override public void terminateInstance(String instanceId) { throw new UnsupportedOperationException(); }
        @Override public void startInstance(String instanceId) { throw new UnsupportedOperationException(); }
        @Override public boolean canReachApi() { return true; }
    }
}
