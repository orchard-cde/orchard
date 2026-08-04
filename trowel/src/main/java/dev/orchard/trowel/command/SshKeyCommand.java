package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.client.OrchardClient;
import dev.orchard.trowel.client.OrchardClient.SshPublicKeyResponse;
import dev.orchard.trowel.ssh.SshKeyPaths;
import dev.orchard.trowel.ssh.SshKeyStore;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;

@CommandLine.Command(
    name = "ssh-key",
    aliases = {"sshkeys"},
    description = "Manage SSH keys registered with Orchard",
    subcommands = {SshKeyCommand.Add.class, SshKeyCommand.List.class, SshKeyCommand.Remove.class}
)
public class SshKeyCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    Trowel parent;

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @CommandLine.Command(name = "add", description = "Generate a local keypair if needed and register its public key")
    public static class Add implements Callable<Integer> {

        @CommandLine.ParentCommand
        SshKeyCommand parent;

        @CommandLine.Option(names = {"-n", "--name"}, description = "Key name (default: default)")
        String name = "default";

        @CommandLine.Option(names = {"-p", "--path"}, description = "Register an existing public key file instead of generating one")
        String path;

        @Override
        public Integer call() {
            try {
                String publicLine;
                if (path != null) {
                    publicLine = Files.readString(Path.of(path)).trim();
                } else {
                    SshKeyStore.loadOrCreate(name);
                    publicLine = Files.readString(SshKeyPaths.publicKey(name)).trim();
                }

                OrchardClient client = new OrchardClient(parent.parent.getServerUrl(), parent.parent.getAuthProvider());
                SshPublicKeyResponse created = client.registerSshPublicKey(name, publicLine);

                System.out.println("Registered SSH key '" + created.name() + "'");
                System.out.println("  fingerprint: " + created.fingerprint());
                if (path != null) {
                    System.out.println("  public key:  " + path);
                } else {
                    System.out.println("  key file:    " + SshKeyPaths.privateKey(name));
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to register SSH key: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "list", aliases = {"ls"}, description = "List registered SSH keys")
    public static class List implements Callable<Integer> {

        @CommandLine.ParentCommand
        SshKeyCommand parent;

        @Override
        public Integer call() {
            try {
                OrchardClient client = new OrchardClient(parent.parent.getServerUrl(), parent.parent.getAuthProvider());
                var keys = client.listSshPublicKeys();

                if (keys.isEmpty()) {
                    System.out.println("No SSH keys registered. Use 'ssh-key add' to register one.");
                    return 0;
                }
                for (SshPublicKeyResponse key : keys) {
                    System.out.printf("%s  %-20s %s%n", key.id(), key.name(), key.fingerprint());
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to list SSH keys: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "remove", aliases = {"rm"}, description = "Remove a registered SSH key")
    public static class Remove implements Callable<Integer> {

        @CommandLine.ParentCommand
        SshKeyCommand parent;

        @CommandLine.Parameters(index = "0", description = "Key id to remove")
        UUID keyId;

        @Override
        public Integer call() {
            try {
                OrchardClient client = new OrchardClient(parent.parent.getServerUrl(), parent.parent.getAuthProvider());
                client.deleteSshPublicKey(keyId);
                System.out.println("Removed SSH key " + keyId);
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to remove SSH key: " + e.getMessage());
                return 1;
            }
        }
    }
}
