package dev.orchard.nursery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for the {@code ssh} argv used across the nursery — both command
 * execution ({@link SshExecutor}) and the QEMU/EC2 SSH-readiness probes. These call sites
 * previously hand-built near-identical option lists; centralising them here keeps connection
 * and liveness options consistent instead of drifting across copies.
 *
 * <p><b>Liveness semantics (issue #138):</b> {@code ConnectTimeout} only bounds the initial
 * handshake. The {@code ServerAlive*} options make ssh send keepalive probes through the
 * transport and disconnect after roughly {@code interval * countMax} seconds of no reply, so a
 * fully unreachable guest (network partition, kernel panic, host gone) breaks the ssh process
 * instead of blocking to the caller's wall-clock timeout. They do <b>not</b> detect a guest whose
 * sshd is still answering keepalives while a command (e.g. {@code devcontainer up}) is wedged —
 * keepalives are answered at the SSH protocol layer regardless of command progress. That case is
 * handled separately by the devcontainer up-timeout converting the hang into a BLIGHT teardown.
 */
public final class SshCommandBuilder {

    /** The login user baked into every nursery guest image. */
    static final String DEFAULT_USER = "cultivator";
    static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;

    private String user = DEFAULT_USER;
    private String host;
    private int port;
    private Path identityKey;
    private int connectTimeoutSeconds = DEFAULT_CONNECT_TIMEOUT_SECONDS;
    private boolean batchMode;
    private String remoteCommand;

    public SshCommandBuilder host(String host) {
        this.host = host;
        return this;
    }

    public SshCommandBuilder port(int port) {
        this.port = port;
        return this;
    }

    /** Adds {@code -i <key>} only if the key file actually exists; null/missing keys are skipped. */
    public SshCommandBuilder identityKey(Path identityKey) {
        this.identityKey = identityKey;
        return this;
    }

    public SshCommandBuilder connectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        return this;
    }

    /** {@code BatchMode=yes} fails fast instead of blocking on an interactive auth prompt. */
    public SshCommandBuilder batchMode(boolean batchMode) {
        this.batchMode = batchMode;
        return this;
    }

    /** The remote command to run. When null the argv ends at the destination (no command). */
    public SshCommandBuilder remoteCommand(String remoteCommand) {
        this.remoteCommand = remoteCommand;
        return this;
    }

    public List<String> build() {
        var cmd = new ArrayList<String>();
        cmd.add("ssh");
        cmd.add("-o"); cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o"); cmd.add("UserKnownHostsFile=/dev/null");
        cmd.add("-o"); cmd.add("ConnectTimeout=" + connectTimeoutSeconds);
        if (batchMode) {
            cmd.add("-o"); cmd.add("BatchMode=yes");
        }
        // See class javadoc: catches an unreachable/dead guest, not a wedged-but-responsive one.
        cmd.add("-o"); cmd.add("ServerAliveInterval=15");
        cmd.add("-o"); cmd.add("ServerAliveCountMax=4");
        if (identityKey != null && Files.exists(identityKey)) {
            cmd.add("-i"); cmd.add(identityKey.toString());
        }
        cmd.add("-p"); cmd.add(String.valueOf(port));
        cmd.add(user + "@" + host);
        if (remoteCommand != null) {
            cmd.add(remoteCommand);
        }
        return cmd;
    }
}
