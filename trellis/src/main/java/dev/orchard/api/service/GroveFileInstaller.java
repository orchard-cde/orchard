package dev.orchard.api.service;

import dev.orchard.trellis.config.KnotholeConfig.KnotholeProperties;
import dev.orchard.vine.ContainerExec;
import dev.orchard.vine.ExecTarget;
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
    private static final String FRUIT_SECRETS_DIR = "/dev/shm/orchard";
    public static final String VM_SECRETS_DIR = "/run/orchard/secrets";
    public static final String VM_SECRETS_PATH = VM_SECRETS_DIR + "/secrets.env";

    private static final String SOURCE_HOOK_LINE = "'\\n[ -r " + HOOK_PATH + " ] && . " + HOOK_PATH + "\\n'";
    static final String BASHRC_GUARD =
        "grep -q orchard-secrets /etc/bash.bashrc 2>/dev/null || printf " + SOURCE_HOOK_LINE
            + " >> /etc/bash.bashrc";
    static final String ZPROFILE_GUARD =
        "if [ -d /etc/zsh ]; then f=/etc/zsh/zprofile; else f=/etc/zprofile; fi; "
            + "grep -q orchard-secrets \"$f\" 2>/dev/null || printf " + SOURCE_HOOK_LINE + " >> \"$f\"";
    private static final String HOOK_RESOURCE = "/knothole/orchard-secrets.sh";
    private static final String HOOK = loadHook();

    private final long timeoutSeconds;

    public GroveFileInstaller(KnotholeProperties properties) {
        this.timeoutSeconds = properties.deliveryTimeout().toSeconds();
    }

    public void install(ExecTarget exec, String containerId, Target target, FileSpec spec)
        throws IOException, InterruptedException {
        exec.runner().execute(command(exec, containerId, target, script(target, spec)), spec.content(),
            timeoutSeconds);
    }

    public void installHook(ExecTarget exec, String containerId, Target target)
        throws IOException, InterruptedException {
        FileSpec spec = new FileSpec(HOOK_PATH, "root", "root", "0644", HOOK.getBytes(StandardCharsets.UTF_8));
        exec.runner().execute(command(exec, containerId, target, hookInstallScript(target, spec)), spec.content(),
            timeoutSeconds);
    }

    static String hookInstallScript(Target target, FileSpec spec) {
        return script(target, spec) + "; umask 022; " + BASHRC_GUARD + "; " + ZPROFILE_GUARD;
    }

    private static String script(Target target, FileSpec spec) {
        return target == Target.FRUIT ? fruitScript(spec) : vmScript(spec);
    }

    public static String hookScript() {
        return HOOK;
    }

    static String fruitScript(FileSpec spec) {
        return preamble(spec) + "d=$(dirname \"$p\"); "
            + parentSteps(spec) + "umask 077; " + writeSteps(spec);
    }

    private static String parentSteps(FileSpec spec) {
        if (!FRUIT_SECRETS_DIR.equals(parentOf(spec.path()))) {
            return "mkdir -p \"$d\"; chmod 755 \"$d\"; ";
        }
        return "if [ -e \"$d\" ] || [ -L \"$d\" ]; then o=$(stat -c %u \"$d\"); "
            + "if [ -L \"$d\" ] || [ \"$o\" != 0 ]; then rm -rf \"$d\"; fi; fi; "
            + "if [ ! -e \"$d\" ] && [ ! -L \"$d\" ]; then mkdir \"$d\"; fi; "
            + "[ ! -L \"$d\" ]; chown root:root \"$d\"; chmod 755 \"$d\"; ";
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    static String vmScript(FileSpec spec) {
        if (!spec.path().startsWith(VM_SECRETS_DIR + "/")) {
            return preamble(spec) + "umask 077; " + writeSteps(spec);
        }
        return "set -e; " + vars(spec) + "; d=" + ShellQuote.quote(VM_SECRETS_DIR)
            + "; trap 'rm -f \"$t\"; mount -o remount,ro \"$d\" 2>/dev/null || true' EXIT; "
            + "mkdir -p \"$d\"; mountpoint -q \"$d\" || mount -t tmpfs -o mode=0755,size=64m tmpfs \"$d\"; "
            + "mount -o remount,rw \"$d\"; umask 077; "
            + writeSteps(spec)
            + "; mount -o remount,ro \"$d\"";
    }

    private static String preamble(FileSpec spec) {
        return "set -e; " + vars(spec) + "; trap 'rm -f \"$t\"' EXIT; ";
    }

    private static String vars(FileSpec spec) {
        return "p=" + ShellQuote.quote(spec.path()) + "; t=" + ShellQuote.quote(spec.path() + ".tmp");
    }

    private static String writeSteps(FileSpec spec) {
        return "rm -f \"$t\"; cat > \"$t\""
            + "; g=$(id -gn " + ShellQuote.quote(spec.groupOfUser()) + ")"
            + "; chown " + ShellQuote.quote(spec.owner()) + ":\"$g\" \"$t\""
            + "; chmod " + ShellQuote.quote(spec.mode()) + " \"$t\""
            + "; mv -f \"$t\" \"$p\"";
    }

    private static String command(ExecTarget exec, String containerId, Target target, String script) {
        if (target == Target.VM) {
            return "sudo sh -c " + ShellQuote.quote(script);
        }
        return ContainerExec.of(exec.runtime().cli(), containerId)
            .interactive(true, false).user("root").shell(script).commandLine();
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
