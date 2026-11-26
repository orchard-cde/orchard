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

/**
 * Installs the secrets file and the shell hook that exports it into a fruit or onto a VM, as root.
 * File content travels over stdin, never on a command line.
 */
@Component
public final class GroveFileInstaller {

    public enum Target { FRUIT, VM }

    public static final String HOOK_PATH = "/etc/profile.d/orchard-secrets.sh";
    public static final String FRUIT_SECRETS_PATH = "/dev/shm/orchard/secrets.env";
    public static final String VM_SECRETS_PATH = "/run/orchard/secrets/secrets.env";

    private static final String SOURCE_HOOK_LINE = "'\\n[ -r " + HOOK_PATH + " ] && . " + HOOK_PATH + "\\n'";
    static final String BASHRC_GUARD =
        "grep -q orchard-secrets /etc/bash.bashrc 2>/dev/null || printf " + SOURCE_HOOK_LINE
            + " >> /etc/bash.bashrc";
    static final String ZPROFILE_GUARD =
        "if [ -d /etc/zsh ]; then f=/etc/zsh/zprofile; else f=/etc/zprofile; fi; "
            + "grep -q orchard-secrets \"$f\" 2>/dev/null || printf " + SOURCE_HOOK_LINE + " >> \"$f\"";

    /** The parent is re-created unless it already is a real directory owned by root. */
    private static final String FRUIT_SECRETS_SCRIPT = """
        set -e
        d=/dev/shm/orchard; p=$d/secrets.env; t=$p.tmp
        trap 'rm -f "$t"' EXIT
        if [ -e "$d" ] || [ -L "$d" ]; then o=$(stat -c %u "$d"); if [ -L "$d" ] || [ "$o" != 0 ]; then rm -rf "$d"; fi; fi
        if [ ! -e "$d" ] && [ ! -L "$d" ]; then mkdir "$d"; fi
        [ ! -L "$d" ]
        chown root:root "$d"; chmod 755 "$d"
        umask 077
        rm -f "$t"; cat > "$t"
        g=$(id -gn @reader@)
        chown root:"$g" "$t"; chmod 0440 "$t"
        mv -f "$t" "$p"
        """;

    /** The directory is a tmpfs that stays read-only except while the file is replaced. */
    private static final String VM_SECRETS_SCRIPT = """
        set -e
        d=/run/orchard/secrets; p=$d/secrets.env; t=$p.tmp
        trap 'rm -f "$t"; mount -o remount,ro "$d" 2>/dev/null || true' EXIT
        mkdir -p "$d"
        mountpoint -q "$d" || mount -t tmpfs -o mode=0755,size=64m tmpfs "$d"
        mount -o remount,rw "$d"
        umask 077
        rm -f "$t"; cat > "$t"
        g=$(id -gn @reader@)
        chown root:"$g" "$t"; chmod 0440 "$t"
        mv -f "$t" "$p"
        mount -o remount,ro "$d"
        """;

    private static final String HOOK_INSTALL_SCRIPT = """
        set -e
        p=/etc/profile.d/orchard-secrets.sh; t=$p.tmp
        trap 'rm -f "$t"' EXIT
        mkdir -p /etc/profile.d; chmod 755 /etc/profile.d
        umask 022
        rm -f "$t"; cat > "$t"
        chown root:root "$t"; chmod 0644 "$t"
        mv -f "$t" "$p"
        """ + BASHRC_GUARD + "\n" + ZPROFILE_GUARD + "\n";

    private static final String HOOK_RESOURCE = "/knothole/orchard-secrets.sh";
    private static final String HOOK = loadHook();

    private final long timeoutSeconds;

    public GroveFileInstaller(KnotholeProperties properties) {
        this.timeoutSeconds = properties.deliveryTimeout().toSeconds();
    }

    /** Writes {@code env} as the secrets file, readable by root and by {@code reader}'s primary group. */
    public void installSecrets(ExecTarget exec, String containerId, Target target, String reader, byte[] env)
        throws IOException, InterruptedException {
        exec.runner().execute(command(exec, containerId, target, secretsScript(target, reader)), env, timeoutSeconds);
    }

    public void installHook(ExecTarget exec, String containerId, Target target)
        throws IOException, InterruptedException {
        exec.runner().execute(command(exec, containerId, target, hookInstallScript()),
            HOOK.getBytes(StandardCharsets.UTF_8), timeoutSeconds);
    }

    public static String hookScript() {
        return HOOK;
    }

    static String secretsScript(Target target, String reader) {
        String script = target == Target.VM ? VM_SECRETS_SCRIPT : FRUIT_SECRETS_SCRIPT;
        return oneLine(script.replace("@reader@", ShellQuote.quote(reader)));
    }

    static String hookInstallScript() {
        return oneLine(HOOK_INSTALL_SCRIPT);
    }

    private static String oneLine(String script) {
        return script.strip().replace("\n", "; ");
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
