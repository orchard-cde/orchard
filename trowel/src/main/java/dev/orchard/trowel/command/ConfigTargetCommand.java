package dev.orchard.trowel.command;

import dev.orchard.trowel.config.ConfigLoader;
import dev.orchard.trowel.config.OrchardConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;

@Command(
    name = "target",
    description = "Manage configuration targets",
    subcommands = {
        ConfigTargetCommand.List.class,
        ConfigTargetCommand.Set.class,
        ConfigTargetCommand.Add.class,
        ConfigTargetCommand.Remove.class
    }
)
public class ConfigTargetCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "list", description = "List all configured targets")
    public static class List implements Callable<Integer> {

        @Override
        public Integer call() {
            OrchardConfig config = ConfigLoader.load();
            if (config == null) {
                System.err.println("No configuration found. Run 'trowel config init' first.");
                return 1;
            }
            System.out.println();
            config.targets().forEach((name, target) -> {
                String marker = name.equals(config.active()) ? "* " : "  ";
                System.out.println(marker + name);
                System.out.println("    server     = " + target.server());
                System.out.println("    cultivator = " + target.cultivator());
            });
            System.out.println();
            return 0;
        }
    }

    @Command(name = "set", description = "Switch active target")
    public static class Set implements Callable<Integer> {

        @Parameters(index = "0", description = "Target name to activate")
        String name;

        @Override
        public Integer call() {
            OrchardConfig config = ConfigLoader.load();
            if (config == null) {
                System.err.println("No configuration found. Run 'trowel config init' first.");
                return 1;
            }
            if (!config.targets().containsKey(name)) {
                System.err.println("Target '" + name + "' not found. Available: " + config.targets().keySet());
                return 1;
            }
            try {
                ConfigLoader.save(new OrchardConfig(name, config.targets()));
                System.out.println("Active target set to '" + name + "'.");
                return 0;
            } catch (IOException e) {
                System.err.println("Failed to save config: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "add", description = "Add a new target")
    public static class Add implements Callable<Integer> {

        @Parameters(index = "0", description = "Target name")
        String name;

        @Option(names = "--server", required = true, description = "Orchard server URL")
        String server;

        @Option(names = "--cultivator", required = true, description = "Cultivator UUID")
        String cultivator;

        @Override
        public Integer call() {
            OrchardConfig config = ConfigLoader.load();
            if (config == null) {
                System.err.println("No configuration found. Run 'trowel config init' first.");
                return 1;
            }
            if (config.targets().containsKey(name)) {
                System.err.println("Target '" + name + "' already exists. Use 'trowel config set --target " + name + "' to update it.");
                return 1;
            }
            var updated = new LinkedHashMap<>(config.targets());
            updated.put(name, new OrchardConfig.Target(server, cultivator));
            try {
                ConfigLoader.save(new OrchardConfig(config.active(), updated));
                System.out.println("Added target '" + name + "'.");
                return 0;
            } catch (IOException e) {
                System.err.println("Failed to save config: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "remove", description = "Remove a target")
    public static class Remove implements Callable<Integer> {

        @Parameters(index = "0", description = "Target name to remove")
        String name;

        @Override
        public Integer call() {
            OrchardConfig config = ConfigLoader.load();
            if (config == null) {
                System.err.println("No configuration found. Run 'trowel config init' first.");
                return 1;
            }
            if (!config.targets().containsKey(name)) {
                System.err.println("Target '" + name + "' not found.");
                return 1;
            }
            if (name.equals(config.active())) {
                System.err.println("Cannot remove the active target. Switch to another target first with 'trowel config target set <name>'.");
                return 1;
            }
            var updated = new LinkedHashMap<>(config.targets());
            updated.remove(name);
            try {
                ConfigLoader.save(new OrchardConfig(config.active(), updated));
                System.out.println("Removed target '" + name + "'.");
                return 0;
            } catch (IOException e) {
                System.err.println("Failed to save config: " + e.getMessage());
                return 1;
            }
        }
    }
}
