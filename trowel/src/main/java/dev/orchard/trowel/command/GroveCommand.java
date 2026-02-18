package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.client.OrchardClient;
import dev.orchard.trowel.client.OrchardClient.GroveResponse;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(
    name = "grove",
    description = "Manage groves (development workspaces)",
    subcommands = {
        GroveCommand.Plant.class,
        GroveCommand.ListGroves.class,
        GroveCommand.Show.class,
        GroveCommand.Clear.class,
        GroveCommand.Connect.class
    }
)
public class GroveCommand implements Callable<Integer> {

    @ParentCommand
    Trowel parent;

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "plant", description = "Plant a new grove from a repository")
    public static class Plant implements Callable<Integer> {

        @ParentCommand
        GroveCommand parent;

        @Parameters(index = "0", description = "Repository URL (e.g., https://github.com/user/repo)")
        String repositoryUrl;

        @Option(names = {"-b", "--branch"}, description = "Branch name", defaultValue = "main")
        String branch;

        @Option(names = {"-n", "--name"}, description = "Grove name")
        String name;

        @Option(names = {"-m", "--machine"}, description = "Machine size: small, medium, large", defaultValue = "small")
        String machineSize;

        @Override
        public Integer call() {
            try {
                System.out.println("\u001B[1;32mPlanting grove...\u001B[0m");
                System.out.println("  Repository: " + repositoryUrl);
                System.out.println("  Branch: " + branch);
                System.out.println("  Machine: " + machineSize);
                System.out.println();

                OrchardClient client = new OrchardClient(
                    parent.parent.getServerUrl(),
                    parent.parent.getCultivatorId()
                );

                GroveResponse grove = client.plantGrove(repositoryUrl, branch, name, machineSize);
                printGrove(grove);

                System.out.println();
                System.out.println("Grove is being planted. Use 'trowel grove show " + grove.id() + "' to check status.");

                return 0;
            } catch (Exception e) {
                System.err.println("Failed to plant grove: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "list", aliases = {"ls"}, description = "List all groves")
    public static class ListGroves implements Callable<Integer> {

        @ParentCommand
        GroveCommand parent;

        @Option(names = {"-a", "--all"}, description = "Show all groves including cleared")
        boolean showAll;

        @Override
        public Integer call() {
            try {
                OrchardClient client = new OrchardClient(
                    parent.parent.getServerUrl(),
                    parent.parent.getCultivatorId()
                );

                List<GroveResponse> groves = client.listGroves();

                if (groves.isEmpty()) {
                    System.out.println("No groves found. Plant one with 'trowel grove plant <repo-url>'");
                    return 0;
                }

                System.out.println();
                System.out.printf("%-36s  %-12s  %-20s  %-15s%n", "ID", "STATE", "NAME", "BRANCH");
                System.out.println("-".repeat(90));

                for (GroveResponse grove : groves) {
                    if (!showAll && "CLEARED".equals(grove.state())) {
                        continue;
                    }
                    String stateIcon = getStateIcon(grove.state());
                    System.out.printf("%-36s  %s %-10s  %-20s  %-15s%n",
                        grove.id(),
                        stateIcon,
                        grove.state(),
                        truncate(grove.name(), 20),
                        grove.branch()
                    );
                }
                System.out.println();

                return 0;
            } catch (Exception e) {
                System.err.println("Failed to list groves: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "show", description = "Show details of a grove")
    public static class Show implements Callable<Integer> {

        @ParentCommand
        GroveCommand parent;

        @Parameters(index = "0", description = "Grove ID")
        UUID groveId;

        @Override
        public Integer call() {
            try {
                OrchardClient client = new OrchardClient(
                    parent.parent.getServerUrl(),
                    parent.parent.getCultivatorId()
                );

                GroveResponse grove = client.getGrove(groveId);
                printGrove(grove);

                return 0;
            } catch (Exception e) {
                System.err.println("Failed to get grove: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "clear", description = "Clear (delete) a grove")
    public static class Clear implements Callable<Integer> {

        @ParentCommand
        GroveCommand parent;

        @Parameters(index = "0", description = "Grove ID")
        UUID groveId;

        @Option(names = {"-f", "--force"}, description = "Force clear without confirmation")
        boolean force;

        @Override
        public Integer call() {
            try {
                if (!force) {
                    System.out.print("Are you sure you want to clear grove " + groveId + "? [y/N] ");
                    int response = System.in.read();
                    if (response != 'y' && response != 'Y') {
                        System.out.println("Cancelled.");
                        return 0;
                    }
                }

                OrchardClient client = new OrchardClient(
                    parent.parent.getServerUrl(),
                    parent.parent.getCultivatorId()
                );

                client.clearGrove(groveId);
                System.out.println("Grove " + groveId + " has been cleared.");

                return 0;
            } catch (Exception e) {
                System.err.println("Failed to clear grove: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "connect", aliases = {"ssh"}, description = "Connect to a grove via SSH")
    public static class Connect implements Callable<Integer> {

        @ParentCommand
        GroveCommand parent;

        @Parameters(index = "0", description = "Grove ID")
        UUID groveId;

        @Override
        public Integer call() {
            try {
                OrchardClient client = new OrchardClient(
                    parent.parent.getServerUrl(),
                    parent.parent.getCultivatorId()
                );

                GroveResponse grove = client.getGrove(groveId);

                if (!"FLOURISHING".equals(grove.state())) {
                    System.err.println("Grove is not ready (state: " + grove.state() + ")");
                    return 1;
                }

                if (grove.sshConnectionString() == null) {
                    System.err.println("SSH connection not available for this grove");
                    return 1;
                }

                System.out.println("Connecting to grove: " + grove.name());
                System.out.println("Command: " + grove.sshConnectionString());
                System.out.println();

                // Execute SSH
                String[] sshParts = grove.sshConnectionString().split(" ");
                ProcessBuilder pb = new ProcessBuilder(sshParts);
                pb.inheritIO();
                Process process = pb.start();
                return process.waitFor();

            } catch (Exception e) {
                System.err.println("Failed to connect: " + e.getMessage());
                return 1;
            }
        }
    }

    // Helper methods
    private static void printGrove(GroveResponse grove) {
        System.out.println();
        System.out.println("Grove: " + grove.name());
        System.out.println("-".repeat(50));
        System.out.println("  ID:          " + grove.id());
        System.out.println("  State:       " + getStateIcon(grove.state()) + " " + grove.state());
        System.out.println("  Repository:  " + grove.repositoryUrl());
        System.out.println("  Branch:      " + grove.branch());
        if (grove.commitSha() != null) {
            System.out.println("  Commit:      " + grove.commitSha());
        }
        System.out.println("  Planted:     " + grove.plantedAt());

        if (grove.seedling() != null) {
            System.out.println();
            System.out.println("  Seedling (VM):");
            System.out.println("    State:     " + grove.seedling().state());
            System.out.println("    IP:        " + grove.seedling().ipAddress());
            System.out.println("    SSH Port:  " + grove.seedling().sshPort());
            System.out.println("    Resources: " + grove.seedling().cpuCores() + " CPU, " +
                grove.seedling().memoryMb() + " MB RAM, " + grove.seedling().diskGb() + " GB disk");
        }

        if (grove.fruit() != null) {
            System.out.println();
            System.out.println("  Fruit (Container):");
            System.out.println("    State:     " + grove.fruit().state());
            System.out.println("    Name:      " + grove.fruit().containerName());
            if (grove.fruit().containerId() != null) {
                System.out.println("    ID:        " + grove.fruit().containerId().substring(0, 12));
            }
        }

        if (grove.sshConnectionString() != null) {
            System.out.println();
            System.out.println("  Connect: " + grove.sshConnectionString());
        }
    }

    private static String getStateIcon(String state) {
        return switch (state) {
            case "PREPARING", "PLANTING", "GROWING" -> "\u23F3"; // hourglass
            case "FLOURISHING" -> "\u2705"; // green check
            case "DORMANT" -> "\uD83D\uDCA4"; // zzz
            case "CLEARING", "CLEARED" -> "\uD83D\uDDD1"; // wastebasket
            case "BLIGHTED" -> "\u274C"; // red x
            default -> "\u2753"; // question mark
        };
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() <= maxLen ? str : str.substring(0, maxLen - 3) + "...";
    }
}
