package dev.orchard.api.service;

import dev.orchard.api.service.GroveFileInstaller.Target;
import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.Secret;
import dev.orchard.core.model.VmPlot;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.trellis.config.KnotholeConfig.KnotholeProperties;
import dev.orchard.vine.ExecTarget;
import dev.orchard.vine.ShellQuote;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Resolves a fruit's declared devcontainer secrets and writes them into the running grove. */
@Component
public final class SecretInjector {

    private static final Logger log = LoggerFactory.getLogger(SecretInjector.class);
    private static final String VM_READER = "cultivator";

    private final SecretService secrets;
    private final GroveFileInstaller installer;
    private final long inspectTimeoutSeconds;

    public SecretInjector(SecretService secrets, GroveFileInstaller installer, KnotholeProperties properties) {
        this.secrets = secrets;
        this.installer = installer;
        this.inspectTimeoutSeconds = properties.inspectTimeout().toSeconds();
    }

    /**
     * Delivers every stored value the fruit declares. A declared name with no stored value is
     * logged and skipped; a stored value that cannot be delivered throws
     * {@link SecretDeliveryException}.
     */
    public void inject(GroveProvider provider, Grove grove, Fruit fruit) {
        Set<String> declared = storableNames(grove, fruit);
        if (declared.isEmpty()) {
            return;
        }
        Map<String, byte[]> values = secrets.resolve(grove.cultivatorId(), declared);
        List<String> missing = declared.stream().filter(n -> !values.containsKey(n)).sorted().toList();
        if (!missing.isEmpty()) {
            log.warn("Grove {} declares secrets that are not stored: {}", grove.id(), missing);
        }
        if (values.isEmpty()) {
            return;
        }
        try {
            deliver(provider, grove.plot(), fruit, SecretEnvFile.render(values));
        } catch (IOException e) {
            throw new SecretDeliveryException("delivering secrets to grove " + grove.id() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretDeliveryException("delivering secrets to grove " + grove.id() + " was interrupted", e);
        }
        log.info("Injected secrets {} into grove {}", values.keySet().stream().sorted().toList(), grove.id());
    }

    private void deliver(GroveProvider provider, Plot plot, Fruit fruit, byte[] env)
        throws IOException, InterruptedException {
        String reader = fruit.remoteUser();
        if (reader == null) {
            throw new SecretDeliveryException(
                "declared secrets can only be delivered to a fruit grown through the devcontainer CLI; "
                    + "this fruit has no remote user, which happens when orchard.nursery.use-devcontainer-cli is false",
                null);
        }
        ExecTarget exec = provider.execTarget(plot);
        if (plot instanceof ContainerPlot && "host".equals(ipcMode(exec, fruit.containerId()))) {
            throw new SecretDeliveryException(
                "declared secrets cannot be delivered to a container started with --ipc=host", null);
        }
        installer.installHook(exec, fruit.containerId(), Target.FRUIT);
        installer.installSecrets(exec, fruit.containerId(), Target.FRUIT, reader, env);
        if (plot instanceof VmPlot) {
            installer.installHook(exec, fruit.containerId(), Target.VM);
            installer.installSecrets(exec, fruit.containerId(), Target.VM, VM_READER, env);
        }
    }

    private static Set<String> storableNames(Grove grove, Fruit fruit) {
        if (!(fruit.seed() instanceof DevcontainerSeed devcontainer) || devcontainer.secrets() == null) {
            return Set.of();
        }
        Set<String> declared = devcontainer.secrets().keySet();
        List<String> unstorable = declared.stream().filter(n -> !Secret.isStorableName(n)).sorted().toList();
        if (!unstorable.isEmpty()) {
            log.warn("Unstorable secret declarations {} for grove {}", unstorable, grove.id());
        }
        return declared.stream().filter(Secret::isStorableName).collect(Collectors.toSet());
    }

    private String ipcMode(ExecTarget exec, String containerId) throws IOException, InterruptedException {
        String command = exec.runtime().cli() + " inspect --format '{{.HostConfig.IpcMode}}' "
            + ShellQuote.quote(containerId);
        return exec.runner().execute(command, inspectTimeoutSeconds).trim();
    }
}
