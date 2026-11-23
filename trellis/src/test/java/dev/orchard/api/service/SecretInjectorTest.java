package dev.orchard.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.orchard.api.service.GroveFileInstaller.FileSpec;
import dev.orchard.api.service.GroveFileInstaller.Target;
import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.DevcontainerSeed.SecretDeclaration;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.Seed;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import dev.orchard.knothole.VaultException;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.vine.CommandRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class SecretInjectorTest {

    private static final String CONTAINER_ID = "c0ffee";

    private final SecretService secrets = mock(SecretService.class);
    private final GroveFileInstaller installer = mock(GroveFileInstaller.class);
    private final GroveProvider provider = mock(GroveProvider.class);
    private final CommandRunner host = mock(CommandRunner.class);
    private final SecretInjector injector = new SecretInjector(secrets, installer);

    @BeforeEach
    void wire() throws Exception {
        lenient().when(provider.host(any())).thenReturn(host);
        lenient().when(provider.runtime(any())).thenReturn(ContainerRuntime.DOCKER);
        lenient().when(host.execute(any())).thenReturn("private");
        lenient().when(host.execute(anyString(), anyLong())).thenReturn("private");
    }

    private static Plot containerPlot() {
        return new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), "owner", null,
            PlotState.ROOTED, Instant.now(), null, Path.of("/h/ws"),
            ContainerRuntime.DOCKER, new ContainerSpec(2, 2048), null);
    }

    private static Plot vmPlot() {
        return new VmPlot(UUID.randomUUID(), null, "owner", "vm-1", PlotState.ROOTED,
            Instant.now(), null, "127.0.0.1", 22, List.of(), VmSpec.small());
    }

    private static Map<String, SecretDeclaration> declared(String... names) {
        Map<String, SecretDeclaration> map = new LinkedHashMap<>();
        for (String name : names) {
            map.put(name, new SecretDeclaration("desc " + name, null));
        }
        return map;
    }

    private static Grove grove(Plot plot) {
        return Grove.plant(UUID.randomUUID(), "g", "https://example.invalid/r.git", "main").withPlot(plot);
    }

    private static Fruit fruit(Grove grove, Seed seed, String remoteUser) {
        Fruit fruit = Fruit.bud(grove.id(), grove.plot().id(), seed).withContainerDetails(CONTAINER_ID, List.of());
        return remoteUser == null ? fruit : fruit.withRemoteEnv("/workspaces/r", remoteUser);
    }

    private static Seed seedDeclaring(String... names) {
        return Seed.devcontainer().name("t").image("ubuntu").secrets(declared(names)).build();
    }

    private static Map<String, byte[]> values(String... namesAndValues) {
        Map<String, byte[]> map = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            map.put(namesAndValues[i], namesAndValues[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return map;
    }

    private FileSpec captureFruitSpec() throws Exception {
        ArgumentCaptor<FileSpec> spec = ArgumentCaptor.forClass(FileSpec.class);
        verify(installer).install(eq(provider), any(), eq(CONTAINER_ID), eq(Target.FRUIT), spec.capture());
        return spec.getValue();
    }

    @Test
    void inject_noDeclaredSecrets_noCalls() {
        Grove grove = grove(containerPlot());
        Seed seed = Seed.devcontainer().name("t").image("ubuntu").build();

        SecretInjector.Outcome outcome = injector.inject(provider, grove, fruit(grove, seed, "vscode"));

        assertThat(outcome).isEqualTo(new SecretInjector.Outcome(List.of(), List.of(), List.of()));
        verifyNoInteractions(secrets, installer, provider, host);
    }

    @Test
    void inject_devfileSeed_noCalls() {
        Grove grove = grove(containerPlot());
        Seed seed = Seed.devfile().name("t").image("ubuntu").build();

        SecretInjector.Outcome outcome = injector.inject(provider, grove, fruit(grove, seed, "vscode"));

        assertThat(outcome.injected()).isEmpty();
        assertThat(outcome.missing()).isEmpty();
        verifyNoInteractions(secrets, installer, provider, host);
    }

    @Test
    void inject_nullRemoteUser_throwsNamingFlag() {
        Grove grove = grove(containerPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(values("A", "a"));

        assertThatThrownBy(() -> injector.inject(provider, grove, fruit(grove, seedDeclaring("A"), null)))
            .isInstanceOf(SecretDeliveryException.class)
            .hasMessageContaining("orchard.nursery.use-devcontainer-cli");
        verifyNoInteractions(installer, host);
    }

    @Test
    void inject_allMissing_nullRemoteUser_warnsWithoutBlight() {
        Grove grove = grove(containerPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(Map.of());

        SecretInjector.Outcome outcome = injector.inject(provider, grove, fruit(grove, seedDeclaring("A", "B"), null));

        assertThat(outcome.missing()).containsExactlyInAnyOrder("A", "B");
        assertThat(outcome.injected()).isEmpty();
        verifyNoInteractions(installer, provider, host);
    }

    @Test
    void inject_rootRemoteUser_usesMode0400() throws Exception {
        Grove grove = grove(containerPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(values("A", "a"));

        injector.inject(provider, grove, fruit(grove, seedDeclaring("A"), "root"));
        FileSpec rootSpec = captureFruitSpec();
        assertThat(rootSpec.mode()).isEqualTo("0400");
        assertThat(rootSpec.groupOfUser()).isEqualTo("root");

        org.mockito.Mockito.clearInvocations(installer);
        injector.inject(provider, grove, fruit(grove, seedDeclaring("A"), "vscode"));
        assertThat(captureFruitSpec().mode()).isEqualTo("0440");
    }

    @Test
    void inject_containerPlot_ipcHost_throws() throws Exception {
        Grove grove = grove(containerPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(values("A", "a"));
        String inspect = "docker inspect --format '{{.HostConfig.IpcMode}}' '" + CONTAINER_ID + "'";
        when(host.execute(eq(inspect), anyLong())).thenReturn("host\n");

        assertThatThrownBy(() -> injector.inject(provider, grove, fruit(grove, seedDeclaring("A"), "vscode")))
            .isInstanceOf(SecretDeliveryException.class)
            .hasMessageContaining("--ipc=host");
        verifyNoInteractions(installer);
    }

    @Test
    void inject_vmPlot_ipcHost_proceeds() throws Exception {
        lenient().when(host.execute(anyString(), anyLong())).thenReturn("host");
        Grove grove = grove(vmPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(values("A", "a"));

        SecretInjector.Outcome outcome = injector.inject(provider, grove, fruit(grove, seedDeclaring("A"), "vscode"));

        assertThat(outcome.injected()).containsExactly("A");
        verify(installer).install(eq(provider), any(), eq(CONTAINER_ID), eq(Target.FRUIT), any());
        verify(installer).install(eq(provider), any(), eq(CONTAINER_ID), eq(Target.VM), any());
    }

    @Test
    void inject_vmPlot_installsFruitAndVmCopies() throws Exception {
        Grove grove = grove(vmPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(values("A", "a"));

        injector.inject(provider, grove, fruit(grove, seedDeclaring("A"), "vscode"));

        byte[] expected = "A='a'\n".getBytes(StandardCharsets.UTF_8);
        ArgumentCaptor<FileSpec> fruitSpec = ArgumentCaptor.forClass(FileSpec.class);
        ArgumentCaptor<FileSpec> vmSpec = ArgumentCaptor.forClass(FileSpec.class);
        InOrder order = inOrder(installer);
        order.verify(installer).installHook(provider, grove.plot(), CONTAINER_ID, Target.FRUIT);
        order.verify(installer).install(eq(provider), eq(grove.plot()), eq(CONTAINER_ID), eq(Target.FRUIT),
            fruitSpec.capture());
        order.verify(installer).installHook(provider, grove.plot(), CONTAINER_ID, Target.VM);
        order.verify(installer).install(eq(provider), eq(grove.plot()), eq(CONTAINER_ID), eq(Target.VM),
            vmSpec.capture());
        order.verifyNoMoreInteractions();
        assertThat(fruitSpec.getValue().path()).isEqualTo(GroveFileInstaller.FRUIT_SECRETS_PATH);
        assertThat(fruitSpec.getValue().owner()).isEqualTo("root");
        assertThat(fruitSpec.getValue().groupOfUser()).isEqualTo("vscode");
        assertThat(fruitSpec.getValue().mode()).isEqualTo("0440");
        assertThat(fruitSpec.getValue().content()).isEqualTo(expected);
        assertThat(vmSpec.getValue().path()).isEqualTo(GroveFileInstaller.VM_SECRETS_PATH);
        assertThat(vmSpec.getValue().owner()).isEqualTo("root");
        assertThat(vmSpec.getValue().groupOfUser()).isEqualTo("cultivator");
        assertThat(vmSpec.getValue().mode()).isEqualTo("0440");
        assertThat(vmSpec.getValue().content()).isEqualTo(expected);
    }

    @Test
    void inject_containerPlot_installsFruitOnly() throws Exception {
        Grove grove = grove(containerPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(values("A", "a"));

        injector.inject(provider, grove, fruit(grove, seedDeclaring("A"), "vscode"));

        InOrder order = inOrder(installer);
        order.verify(installer).installHook(provider, grove.plot(), CONTAINER_ID, Target.FRUIT);
        order.verify(installer).install(eq(provider), eq(grove.plot()), eq(CONTAINER_ID), eq(Target.FRUIT), any());
        order.verifyNoMoreInteractions();
        verify(host).execute(eq("docker inspect --format '{{.HostConfig.IpcMode}}' '" + CONTAINER_ID + "'"),
            anyLong());
    }

    @Test
    void inject_allMissing_writesNothing_reportsMissing() {
        Grove grove = grove(vmPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(Map.of());

        SecretInjector.Outcome outcome = injector.inject(provider, grove, fruit(grove, seedDeclaring("A", "B"), "vscode"));

        assertThat(outcome.missing()).containsExactly("A", "B");
        assertThat(outcome.injected()).isEmpty();
        verifyNoInteractions(installer, host);
    }

    @Test
    void inject_deletedSinceLastStart_warnsAndSucceeds() throws Exception {
        Grove grove = grove(containerPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(values("A", "a"));

        SecretInjector.Outcome outcome = injector.inject(provider, grove, fruit(grove, seedDeclaring("A", "B"), "vscode"));

        assertThat(outcome.injected()).containsExactly("A");
        assertThat(outcome.missing()).containsExactly("B");
        verify(installer).install(eq(provider), any(), eq(CONTAINER_ID), eq(Target.FRUIT), any());
        verify(secrets).resolve(grove.cultivatorId(), Set.of("A", "B"));
    }

    @Test
    void inject_installerFails_throwsDelivery() throws Exception {
        Grove grove = grove(containerPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(values("A", "a"));
        doThrow(new IOException("exit 1")).when(installer)
            .install(any(), any(), any(), eq(Target.FRUIT), any());

        assertThatThrownBy(() -> injector.inject(provider, grove, fruit(grove, seedDeclaring("A"), "vscode")))
            .isInstanceOf(SecretDeliveryException.class)
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void inject_vaultEntryMissing_throwsDelivery() {
        Grove grove = grove(containerPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection()))
            .thenThrow(new IllegalStateException("Vault entry missing for secret A"));

        assertThatThrownBy(() -> injector.inject(provider, grove, fruit(grove, seedDeclaring("A"), "vscode")))
            .isInstanceOf(SecretDeliveryException.class)
            .hasCauseInstanceOf(IllegalStateException.class);
        verifyNoInteractions(installer);
    }

    @Test
    void inject_vaultExceptionDuringResolve_throwsDelivery() {
        Grove grove = grove(containerPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection()))
            .thenThrow(new VaultException("cannot decrypt 'k'"));

        assertThatThrownBy(() -> injector.inject(provider, grove, fruit(grove, seedDeclaring("A"), "vscode")))
            .isInstanceOf(SecretDeliveryException.class)
            .hasCauseInstanceOf(VaultException.class);
        verifyNoInteractions(installer);
    }

    @Test
    void inject_invalidDeclaredName_isSkippedNotMissing() throws Exception {
        Grove grove = grove(containerPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(values("A", "a"));

        SecretInjector.Outcome outcome = injector.inject(provider, grove,
            fruit(grove, seedDeclaring("A", "bad-name", "B"), "vscode"));

        assertThat(outcome.injected()).containsExactly("A");
        assertThat(outcome.missing()).containsExactly("B");
        assertThat(outcome.skipped()).containsExactly("bad-name");
        verify(secrets).resolve(grove.cultivatorId(), Set.of("A", "B"));
    }

    @Test
    void inject_onlyInvalidDeclaredNames_neverResolves() {
        Grove grove = grove(containerPlot());

        SecretInjector.Outcome outcome = injector.inject(provider, grove,
            fruit(grove, seedDeclaring("bad-name"), "vscode"));

        assertThat(outcome).isEqualTo(new SecretInjector.Outcome(List.of(), List.of(), List.of("bad-name")));
        verifyNoInteractions(secrets, installer, host);
    }

    @Test
    void inject_interrupted_restoresFlag() throws Exception {
        Grove grove = grove(containerPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(values("A", "a"));
        doThrow(new InterruptedException()).when(installer).installHook(any(), any(), any(), any());

        try {
            assertThatThrownBy(() -> injector.inject(provider, grove, fruit(grove, seedDeclaring("A"), "vscode")))
                .isInstanceOf(SecretDeliveryException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void inject_valueNeverInCommandString() {
        String value = "s3cr3t-'value'-xyz";
        RecordingRunner runner = new RecordingRunner(value);
        GroveProvider realProvider = mock(GroveProvider.class);
        when(realProvider.host(any())).thenReturn(runner);
        lenient().when(realProvider.runtime(any())).thenReturn(ContainerRuntime.DOCKER);
        SecretInjector real = new SecretInjector(secrets, new GroveFileInstaller());
        Grove grove = grove(vmPlot());
        when(secrets.resolve(eq(grove.cultivatorId()), anyCollection())).thenReturn(values("TOKEN", value));

        SecretInjector.Outcome outcome = real.inject(realProvider, grove, fruit(grove, seedDeclaring("TOKEN"), "vscode"));

        assertThat(outcome.injected()).containsExactly("TOKEN");
        assertThat(runner.commands).isNotEmpty();
        assertThat(runner.stdins).anySatisfy(bytes ->
            assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("s3cr3t-'\\''value'\\''-xyz"));
    }

    private static final class RecordingRunner implements CommandRunner {
        private final String value;
        final List<String> commands = new ArrayList<>();
        final List<byte[]> stdins = new ArrayList<>();

        RecordingRunner(String value) {
            this.value = value;
        }

        private void check(String command) {
            if (command.contains(value) || command.contains("s3cr3t")) {
                fail("secret value appeared in a command string: " + command);
            }
            commands.add(command);
        }

        @Override
        public String execute(String command) {
            check(command);
            return "";
        }

        @Override
        public String execute(String command, long timeoutSeconds) {
            check(command);
            return "";
        }

        @Override
        public String execute(String command, byte[] stdin, long timeoutSeconds) {
            check(command);
            stdins.add(stdin);
            return "";
        }

        @Override
        public void executeStreaming(String command, Consumer<String> lineConsumer, long timeoutSeconds) {
            check(command);
        }

        @Override
        public Optional<String> readFile(String remotePath) {
            return Optional.empty();
        }
    }
}
