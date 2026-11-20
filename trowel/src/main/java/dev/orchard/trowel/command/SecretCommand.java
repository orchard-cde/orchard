package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.client.OrchardClient;
import dev.orchard.trowel.client.OrchardClient.SecretResponse;
import picocli.CommandLine;

import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(
    name = "secret",
    description = "Manage secrets in the Orchard secret store",
    subcommands = {SecretCommand.Set.class, SecretCommand.List.class, SecretCommand.Remove.class}
)
public class SecretCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    Trowel parent;

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @CommandLine.Command(name = "set", description = "Create or rotate a secret; the value is read from --value-file, stdin, or a prompt")
    public static class Set implements Callable<Integer> {

        @CommandLine.ParentCommand
        SecretCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @CommandLine.Parameters(index = "0", description = "Secret name")
        String name;

        @CommandLine.Parameters(index = "1..*", arity = "0..*", hidden = true)
        java.util.List<String> extra;

        @CommandLine.Option(names = "--value-file", description = "Read the value from this file")
        Path valueFile;

        @Override
        public Integer call() {
            if (extra != null && !extra.isEmpty()) {
                System.err.println("The secret value cannot be given on the command line; use --value-file, pipe it on stdin, or enter it at the prompt.");
                spec.commandLine().usage(System.err);
                return 2;
            }
            try {
                String value = readValue();
                OrchardClient client = new OrchardClient(parent.parent.getServerUrl(), parent.parent.getAuthProvider());
                SecretResponse stored = client.putSecret(name, value);
                System.out.println("Stored " + stored.name() + " (rotated " + stored.rotatedAt() + ")");
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to store secret: " + e.getMessage());
                return 1;
            }
        }

        private String readValue() throws IOException {
            if (valueFile != null) {
                return Files.readString(valueFile, StandardCharsets.UTF_8);
            }
            Console console = System.console();
            if (console == null) {
                String raw = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                return raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
            }
            char[] chars = console.readPassword("Value for %s: ", name);
            if (chars == null) {
                throw new IOException("no value entered");
            }
            return new String(chars);
        }
    }

    @CommandLine.Command(name = "list", aliases = {"ls"}, description = "List secrets (names and timestamps only)")
    public static class List implements Callable<Integer> {

        @CommandLine.ParentCommand
        SecretCommand parent;

        @Override
        public Integer call() {
            try {
                OrchardClient client = new OrchardClient(parent.parent.getServerUrl(), parent.parent.getAuthProvider());
                var secrets = client.listSecrets();
                if (secrets.isEmpty()) {
                    System.out.println("No secrets stored. Use 'secret set NAME' to add one.");
                    return 0;
                }
                System.out.printf("%-32s %s %s%n", "NAME", "CREATED", "ROTATED");
                for (SecretResponse secret : secrets) {
                    System.out.printf("%-32s %s %s%n", secret.name(), secret.createdAt(), secret.rotatedAt());
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to list secrets: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "remove", aliases = {"rm"}, description = "Remove a secret")
    public static class Remove implements Callable<Integer> {

        @CommandLine.ParentCommand
        SecretCommand parent;

        @CommandLine.Parameters(index = "0", description = "Secret name to remove")
        String name;

        @CommandLine.Option(names = {"-f", "--force"}, description = "Force removal without confirmation")
        boolean force;

        @Override
        public Integer call() {
            try {
                if (!force) {
                    System.out.print("Are you sure you want to remove secret " + name + "? [y/N] ");
                    int response = System.in.read();
                    if (response != 'y' && response != 'Y') {
                        System.out.println("Cancelled.");
                        return 0;
                    }
                }

                OrchardClient client = new OrchardClient(parent.parent.getServerUrl(), parent.parent.getAuthProvider());
                client.deleteSecret(name);
                System.out.println("Removed secret " + name);
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to remove secret: " + e.getMessage());
                return 1;
            }
        }
    }
}
