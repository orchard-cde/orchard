package dev.orchard.api.service;

import dev.orchard.api.service.GroveFileInstaller.FileSpec;
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

    private final SecretService secrets;
    private final GroveFileInstaller installer;
    private final long inspectTimeoutSeconds;

    public SecretInjector(SecretService secrets, GroveFileInstaller installer, KnotholeProperties properties) {
        this.secrets = secrets;
        this.installer = installer;
        this.inspectTimeoutSeconds = properties.inspectTimeout().toSeconds();
    }

    public record Outcome(List<String> injected, List<String> missing, List<String> skipped) {}

    public Outcome inject(GroveProvider provider, Grove grove, Fruit fruit) {
        Set<String> declared = fruit.seed() instanceof DevcontainerSeed dc && dc.secrets() != null
            ? dc.secrets().keySet() : Set.of();
        if (declared.isEmpty()) {
            return new Outcome(List.of(), List.of(), List.of());
        }
        List<String> unstorable = declared.stream()
            .filter(n -> !Secret.isStorableName(n)).sorted().toList();
        if (!unstorable.isEmpty()) {
            log.warn("Unstorable secret declarations {} for grove {}", unstorable, grove.id());
        }
        Set<String> storable = declared.stream()
            .filter(Secret::isStorableName).collect(Collectors.toSet());
        if (storable.isEmpty()) {
            return new Outcome(List.of(), List.of(), unstorable);
        }
        Plot plot = grove.plot();
        try {
            Map<String, byte[]> values = secrets.resolve(grove.cultivatorId(), storable);
            List<String> missing = storable.stream().filter(n -> !values.containsKey(n)).sorted().toList();
            if (values.isEmpty()) {
                return new Outcome(List.of(), missing, unstorable);
            }
            String remoteUser = fruit.remoteUser();
            if (remoteUser == null) {
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
            byte[] rendered = SecretEnvFile.render(values);
            String fruitMode = "root".equals(remoteUser) ? "0400" : "0440";
            installer.installHook(exec, fruit.containerId(), Target.FRUIT);
            installer.install(exec, fruit.containerId(), Target.FRUIT,
                new FileSpec(GroveFileInstaller.FRUIT_SECRETS_PATH, "root", remoteUser, fruitMode, rendered));
            if (plot instanceof VmPlot) {
                installer.installHook(exec, fruit.containerId(), Target.VM);
                installer.install(exec, fruit.containerId(), Target.VM,
                    new FileSpec(GroveFileInstaller.VM_SECRETS_PATH, "root", "cultivator", "0440", rendered));
            }
            List<String> injected = values.keySet().stream().sorted().toList();
            log.info("Injected secrets {} into grove {}", injected, grove.id());
            return new Outcome(injected, missing, unstorable);
        } catch (SecretDeliveryException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new SecretDeliveryException("delivering secrets to grove " + grove.id() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretDeliveryException("delivering secrets to grove " + grove.id() + " was interrupted", e);
        }
    }

    private String ipcMode(ExecTarget exec, String containerId) throws IOException, InterruptedException {
        String command = exec.runtime().cli() + " inspect --format '{{.HostConfig.IpcMode}}' "
            + ShellQuote.quote(containerId);
        return exec.runner().execute(command, inspectTimeoutSeconds).trim();
    }
}
