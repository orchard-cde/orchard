package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.client.OrchardClient;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(
    name = "bee",
    description = "Manage AI coding assistant bees",
    subcommands = {
        BeeCommand.Install.class,
        BeeCommand.ListBees.class,
        BeeCommand.Show.class,
        BeeCommand.Wake.class,
        BeeCommand.Smoke.class,
        BeeCommand.Status.class
    }
)
public class BeeCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    Trowel parent;

    @Option(names = {"-g", "--grove"}, description = "Grove ID (or set ORCHARD_GROVE_ID)")
    String groveId;

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    UUID resolveGroveId() {
        if (groveId != null) {
            return UUID.fromString(groveId);
        }
        String env = System.getenv("ORCHARD_GROVE_ID");
        if (env != null) {
            return UUID.fromString(env);
        }
        System.err.println("No grove specified. Use --grove <id> or set ORCHARD_GROVE_ID.");
        return null;
    }

    private static void printBee(OrchardClient.BeeResponse bee) {
        System.out.println();
        System.out.println("Bee: " + bee.type());
        System.out.println("-".repeat(50));
        System.out.println("  ID:           " + bee.id());
        System.out.println("  Grove:        " + bee.groveId());
        System.out.println("  Type:         " + bee.type());
        System.out.println("  State:        " + getBeeStateIcon(bee.state()) + " " + bee.state());
        System.out.println("  Process ID:   " + (bee.processId() != null ? bee.processId() : "--"));
        System.out.println("  Hatched:      " + (bee.hatchedAt() != null ? bee.hatchedAt() : "--"));
        System.out.println("  Started:      " + (bee.startedAt() != null ? bee.startedAt() : "--"));
        System.out.println("  Stopped:      " + (bee.stoppedAt() != null ? bee.stoppedAt() : "--"));
    }

    private static String getBeeStateIcon(String state) {
        return switch (state) {
            case "HATCHING" -> "\u23F3";       // hourglass
            case "HIBERNATING" -> "\uD83D\uDCA4"; // zzz
            case "BUZZING" -> "\uD83D\uDC1D";   // bee
            case "POLLINATING" -> "\uD83C\uDF3B"; // sunflower
            case "SMOKED" -> "\u274C";          // red x
            default -> "\u2753";                // question mark
        };
    }

    @Command(name = "install", aliases = {"add"}, description = "Install a new bee in a grove")
    public static class Install implements Callable<Integer> {

        @CommandLine.ParentCommand
        BeeCommand parent;

        @CommandLine.Parameters(index = "0", description = "Bee type: claude-code, gemini, codex, kiro, custom, opencode")
        String beeType;

        @CommandLine.Option(names = {"-v", "--version"}, description = "Version string (e.g., latest, 1.2.3)")
        String version;

        @Override
        public Integer call() {
            try {
                UUID groveId = parent.resolveGroveId();
                if (groveId == null) {
                    return 1;
                }

                String normalizedType = beeType.toUpperCase().replace('-', '_');

                OrchardClient client = new OrchardClient(
                    parent.parent.getServerUrl(),
                    parent.parent.getCultivatorId()
                );

                OrchardClient.BeeResponse bee = client.installBee(groveId, normalizedType, version);
                printBee(bee);

                System.out.println();
                System.out.println("Bee installed. Use 'trowel bee show " + bee.id() + "' to check status.");

                return 0;
            } catch (Exception e) {
                System.err.println("Failed to install bee: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "list", aliases = {"ls"}, description = "List all bees in a grove")
    public static class ListBees implements Callable<Integer> {

        @CommandLine.ParentCommand
        BeeCommand parent;

        @Override
        public Integer call() {
            try {
                UUID groveId = parent.resolveGroveId();
                if (groveId == null) {
                    return 1;
                }

                OrchardClient client = new OrchardClient(
                    parent.parent.getServerUrl(),
                    parent.parent.getCultivatorId()
                );

                List<OrchardClient.BeeResponse> bees = client.listBees(groveId);

                if (bees.isEmpty()) {
                    System.out.println("No bees found. Install one with 'trowel bee install <type> --grove " + groveId + "'");
                    return 0;
                }

                System.out.println();
                System.out.printf("%-15s  %-14s  %-36s  %-20s%n", "TYPE", "STATE", "ID", "HATCHED");
                System.out.println("-".repeat(90));

                for (OrchardClient.BeeResponse bee : bees) {
                    String stateIcon = getBeeStateIcon(bee.state());
                    System.out.printf("%-15s  %s %-12s  %-36s  %-20s%n",
                        bee.type(),
                        stateIcon,
                        bee.state(),
                        bee.id(),
                        bee.hatchedAt() != null ? bee.hatchedAt() : "--"
                    );
                }
                System.out.println();

                return 0;
            } catch (Exception e) {
                System.err.println("Failed to list bees: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "show", description = "Show details of a bee")
    public static class Show implements Callable<Integer> {

        @CommandLine.ParentCommand
        BeeCommand parent;

        @CommandLine.Parameters(index = "0", description = "Bee ID")
        UUID beeId;

        @Override
        public Integer call() {
            try {
                UUID groveId = parent.resolveGroveId();
                if (groveId == null) {
                    return 1;
                }

                OrchardClient client = new OrchardClient(
                    parent.parent.getServerUrl(),
                    parent.parent.getCultivatorId()
                );

                OrchardClient.BeeResponse bee = client.showBee(groveId, beeId);
                printBee(bee);

                return 0;
            } catch (Exception e) {
                System.err.println("Failed to get bee: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "wake", description = "Wake a hibernating bee")
    public static class Wake implements Callable<Integer> {

        @CommandLine.ParentCommand
        BeeCommand parent;

        @CommandLine.Parameters(index = "0", description = "Bee ID")
        UUID beeId;

        @Override
        public Integer call() {
            try {
                UUID groveId = parent.resolveGroveId();
                if (groveId == null) {
                    return 1;
                }

                OrchardClient client = new OrchardClient(
                    parent.parent.getServerUrl(),
                    parent.parent.getCultivatorId()
                );

                OrchardClient.BeeResponse bee = client.wakeBee(groveId, beeId);
                System.out.println("Bee " + bee.type() + " is waking up.");
                printBee(bee);

                return 0;
            } catch (Exception e) {
                System.err.println("Failed to wake bee: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "smoke", description = "Smoke a bee (shut down)")
    public static class Smoke implements Callable<Integer> {

        @CommandLine.ParentCommand
        BeeCommand parent;

        @CommandLine.Parameters(index = "0", description = "Bee ID")
        UUID beeId;

        @Override
        public Integer call() {
            try {
                UUID groveId = parent.resolveGroveId();
                if (groveId == null) {
                    return 1;
                }

                OrchardClient client = new OrchardClient(
                    parent.parent.getServerUrl(),
                    parent.parent.getCultivatorId()
                );

                OrchardClient.BeeResponse bee = client.smokeBee(groveId, beeId);
                System.out.println("Bee " + bee.type() + " has been smoked.");
                printBee(bee);

                return 0;
            } catch (Exception e) {
                System.err.println("Failed to smoke bee: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "status", description = "Show swarm status for a grove")
    public static class Status implements Callable<Integer> {

        @CommandLine.ParentCommand
        BeeCommand parent;

        @Override
        public Integer call() {
            try {
                UUID groveId = parent.resolveGroveId();
                if (groveId == null) {
                    return 1;
                }

                OrchardClient client = new OrchardClient(
                    parent.parent.getServerUrl(),
                    parent.parent.getCultivatorId()
                );

                OrchardClient.SwarmStatusResponse status = client.getSwarmStatus(groveId);

                System.out.println();
                System.out.println("Swarm Status: " + status.totalBees() + " total");
                if (status.byState() != null && !status.byState().isEmpty()) {
                    status.byState().entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .forEach(entry -> System.out.println("  " + entry.getKey() + ": " + entry.getValue()));
                }
                System.out.println();

                return 0;
            } catch (Exception e) {
                System.err.println("Failed to get swarm status: " + e.getMessage());
                return 1;
            }
        }
    }
}
