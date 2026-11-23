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
import dev.orchard.vine.ShellQuote;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Resolves a fruit's declared devcontainer secrets and writes them into the running grove. */
@Component
public final class SecretInjector {

    private static final Logger log = LoggerFactory.getLogger(SecretInjector.class);
    private static final long INSPECT_TIMEOUT_SECONDS = 30;

    private final SecretService secrets;
    private final GroveFileInstaller installer;

    public SecretInjector(SecretService secrets, GroveFileInstaller installer) {
        this.secrets = secrets;
        this.installer = installer;
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
                    "declared secrets need the devcontainer CLI path (orchard.nursery.use-devcontainer-cli=false)",
                    null);
            }
            if (plot instanceof ContainerPlot && "host".equals(ipcMode(provider, plot, fruit.containerId()))) {
                throw new SecretDeliveryException(
                    "declared secrets cannot be delivered to a container started with --ipc=host", null);
            }
            SecretEnvFile.Rendered rendered = SecretEnvFile.render(values);
            String fruitMode = "root".equals(remoteUser) ? "0400" : "0440";
            installer.installHook(provider, plot, fruit.containerId(), Target.FRUIT);
            installer.install(provider, plot, fruit.containerId(), Target.FRUIT,
                new FileSpec(GroveFileInstaller.FRUIT_SECRETS_PATH, "root", remoteUser, fruitMode, rendered.bytes()));
            if (plot instanceof VmPlot) {
                installer.installHook(provider, plot, fruit.containerId(), Target.VM);
                installer.install(provider, plot, fruit.containerId(), Target.VM,
                    new FileSpec(GroveFileInstaller.VM_SECRETS_PATH, "root", "cultivator", "0440", rendered.bytes()));
            }
            List<String> injected = values.keySet().stream()
                .filter(n -> !rendered.skippedNames().contains(n)).sorted().toList();
            log.info("Injected secrets {} into grove {}", injected, grove.id());
            if (!rendered.skippedNames().isEmpty()) {
                log.warn("Skipped secrets with invalid names {} for grove {}", rendered.skippedNames(), grove.id());
            }
            List<String> skipped = Stream.concat(unstorable.stream(), rendered.skippedNames().stream())
                .distinct().sorted().toList();
            return new Outcome(injected, missing, skipped);
        } catch (SecretDeliveryException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new SecretDeliveryException("delivering secrets to grove " + grove.id() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretDeliveryException("delivering secrets to grove " + grove.id() + " was interrupted", e);
        }
    }

    private static String ipcMode(GroveProvider provider, Plot plot, String containerId)
        throws IOException, InterruptedException {
        String command = provider.runtime(plot).cli() + " inspect --format '{{.HostConfig.IpcMode}}' "
            + ShellQuote.quote(containerId);
        return provider.host(plot).execute(command, INSPECT_TIMEOUT_SECONDS).trim();
    }
}
