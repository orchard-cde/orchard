package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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

    @Command(name = "install", aliases = {"add"}, description = "Install a new bee")
    public static class Install implements Callable<Integer> {
        @CommandLine.ParentCommand BeeCommand parent;
        @Override public Integer call() { return 0; }
    }

    @Command(name = "list", aliases = {"ls"}, description = "List all bees in a grove")
    public static class ListBees implements Callable<Integer> {
        @CommandLine.ParentCommand BeeCommand parent;
        @Override public Integer call() { return 0; }
    }

    @Command(name = "show", description = "Show details of a bee")
    public static class Show implements Callable<Integer> {
        @CommandLine.ParentCommand BeeCommand parent;
        @Override public Integer call() { return 0; }
    }

    @Command(name = "wake", description = "Wake a hibernating bee")
    public static class Wake implements Callable<Integer> {
        @CommandLine.ParentCommand BeeCommand parent;
        @Override public Integer call() { return 0; }
    }

    @Command(name = "smoke", description = "Smoke a bee (shut down)")
    public static class Smoke implements Callable<Integer> {
        @CommandLine.ParentCommand BeeCommand parent;
        @Override public Integer call() { return 0; }
    }

    @Command(name = "status", description = "Show swarm status for a grove")
    public static class Status implements Callable<Integer> {
        @CommandLine.ParentCommand BeeCommand parent;
        @Override public Integer call() { return 0; }
    }
}
