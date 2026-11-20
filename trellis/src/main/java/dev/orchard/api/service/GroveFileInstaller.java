package dev.orchard.api.service;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.Plot;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.vine.ContainerExec;
import dev.orchard.vine.ShellQuote;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** Writes memory-backed files into a fruit or onto a VM, feeding the bytes over stdin. */
@Component
public final class GroveFileInstaller {

    public enum Target { FRUIT, VM }

    public record FileSpec(String path, String owner, String groupOfUser, String mode, byte[] content) {}

    public static final String HOOK_PATH = "/etc/profile.d/orchard-secrets.sh";
    public static final String FRUIT_SECRETS_PATH = "/dev/shm/orchard/secrets.env";
    public static final String VM_SECRETS_DIR = "/run/orchard/secrets";
    public static final String VM_SECRETS_PATH = VM_SECRETS_DIR + "/secrets.env";

    private static final long TIMEOUT_SECONDS = 60;
    private static final String BASHRC_GUARD =
        "grep -q orchard-secrets /etc/bash.bashrc 2>/dev/null || printf "
            + "'\\n[ -r /etc/profile.d/orchard-secrets.sh ] && . /etc/profile.d/orchard-secrets.sh\\n' "
            + ">> /etc/bash.bashrc";
    private static final String HOOK_RESOURCE = "/knothole/orchard-secrets.sh";
    private static final String HOOK = loadHook();

    public void install(GroveProvider provider, Plot plot, String containerId, Target target, FileSpec spec)
        throws IOException, InterruptedException {
        String script = target == Target.FRUIT ? fruitScript(spec) : vmScript(spec);
        String command = command(provider, plot, containerId, target, script);
        provider.host(plot).execute(command, spec.content(), TIMEOUT_SECONDS);
    }

    public void installHook(GroveProvider provider, Plot plot, String containerId, Target target)
        throws IOException, InterruptedException {
        install(provider, plot, containerId, target,
            new FileSpec(HOOK_PATH, "root", "root", "0644", HOOK.getBytes(StandardCharsets.UTF_8)));
        provider.host(plot).execute(command(provider, plot, containerId, target, BASHRC_GUARD), TIMEOUT_SECONDS);
    }

    public static String hookScript() {
        return HOOK;
    }

    static String fruitScript(FileSpec spec) {
        return "set -e; d=$(dirname " + ShellQuote.quote(spec.path())
            + "); mkdir -p \"$d\"; chmod 755 \"$d\"; umask 077; " + writeSteps(spec);
    }

    static String vmScript(FileSpec spec) {
        if (!spec.path().startsWith(VM_SECRETS_DIR + "/")) {
            return "set -e; umask 077; " + writeSteps(spec);
        }
        String dir = ShellQuote.quote(VM_SECRETS_DIR);
        return "set -e; mkdir -p " + dir + "; mountpoint -q " + dir
            + " || mount -t tmpfs -o mode=0755,size=1m tmpfs " + dir
            + "; mount -o remount,rw " + dir + "; umask 077; "
            + writeSteps(spec)
            + "; mount -o remount,ro " + dir;
    }

    private static String writeSteps(FileSpec spec) {
        String path = ShellQuote.quote(spec.path());
        String tmp = ShellQuote.quote(spec.path() + ".tmp");
        return "cat > " + tmp
            + "; chown " + ShellQuote.quote(spec.owner()) + ":\"$(id -gn "
            + ShellQuote.quote(spec.groupOfUser()) + ")\" " + tmp
            + "; chmod " + ShellQuote.quote(spec.mode()) + " " + tmp
            + "; mv -f " + tmp + " " + path;
    }

    private static String command(GroveProvider provider, Plot plot, String containerId, Target target,
                                  String script) {
        if (target == Target.VM) {
            if (plot instanceof ContainerPlot) {
                throw new IllegalArgumentException("Target.VM requires a VM plot");
            }
            return "sudo sh -c " + ShellQuote.quote(script);
        }
        return ShellQuote.join(ContainerExec.of(provider.runtime(plot).cli(), containerId)
            .interactive(true, false).user("root").shell(script).argv());
    }

    private static String loadHook() {
        try (InputStream in = GroveFileInstaller.class.getResourceAsStream(HOOK_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + HOOK_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
