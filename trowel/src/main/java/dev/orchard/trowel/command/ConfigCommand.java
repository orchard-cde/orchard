package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.config.ConfigLoader;
import dev.orchard.trowel.config.OrchardConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.concurrent.Callable;

@Command(
    name = "config",
    description = "Configure the Trowel CLI",
    subcommands = {
        ConfigCommand.Show.class,
        ConfigCommand.Set.class,
        ConfigCommand.Init.class,
        ConfigTargetCommand.class
    }
)
public class ConfigCommand implements Callable<Integer> {

    @ParentCommand
    Trowel parent;

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "show", description = "Show current configuration")
    public static class Show implements Callable<Integer> {

        @ParentCommand
        ConfigCommand parent;

        @Override
        public Integer call() {
            System.out.println();
            System.out.println("Trowel CLI Configuration");
            System.out.println("-".repeat(40));
            System.out.println();

            if (Files.exists(ConfigLoader.tomlFile())) {
                System.out.println("  Config file: " + ConfigLoader.tomlFile());
                OrchardConfig config = ConfigLoader.load();
                if (config != null) {
                    System.out.println("  Active target: " + config.active());
                    System.out.println();
                    System.out.println("  Targets:");
                    config.targets().forEach((name, target) -> {
                        String marker = name.equals(config.active()) ? "* " : "  ";
                        System.out.println("    " + marker + name + (name.equals(config.active()) ? " (active)" : ""));
                        System.out.println("        server     = " + target.server());
                        System.out.println("        cultivator = " + target.cultivator());
                    });
                }
            } else if (Files.exists(ConfigLoader.legacyFile())) {
                System.out.println("  Config file: " + ConfigLoader.legacyFile() + " (legacy format)");
                System.out.println("  Run 'trowel config init' to migrate to TOML.");
                System.out.println();
                try {
                    Properties props = new Properties();
                    props.load(Files.newBufferedReader(ConfigLoader.legacyFile()));
                    props.forEach((k, v) -> System.out.println("  " + k + " = " + v));
                } catch (IOException e) {
                    System.err.println("Failed to read config: " + e.getMessage());
                    return 1;
                }
            } else {
                System.out.println("  (no configuration file found)");
                System.out.println();
                System.out.println("  Run 'trowel config init' to create one.");
                System.out.println();
                return 0;
            }

            System.out.println();
            System.out.println("Current effective settings:");
            System.out.println("  server     = " + parent.parent.getServerUrl());
            System.out.println("  cultivator = " + parent.parent.getCultivatorId());
            System.out.println();

            return 0;
        }
    }

    @Command(name = "set", description = "Set a configuration value on the active (or named) target")
    public static class Set implements Callable<Integer> {

        @ParentCommand
        ConfigCommand parent;

        @Option(names = "--server", description = "Orchard server URL")
        String server;

        @Option(names = "--cultivator", description = "Your cultivator ID")
        String cultivatorId;

        @Option(names = {"-t", "--target"}, description = "Target to update (default: active target)")
        String targetName;

        @Override
        public Integer call() {
            OrchardConfig config = ConfigLoader.load();
            if (config == null || !Files.exists(ConfigLoader.tomlFile())) {
                System.err.println("No config.toml found. Run 'trowel config init' first.");
                return 1;
            }

            String name = targetName != null ? targetName : config.active();
            OrchardConfig.Target existing = config.targets().get(name);
            if (existing == null) {
                System.err.println("Target '" + name + "' not found. Use 'trowel config target add' to create it.");
                return 1;
            }

            String newServer = server != null ? server : existing.server();
            String newCultivator = cultivatorId != null ? cultivatorId : existing.cultivator();
            var updated = new LinkedHashMap<>(config.targets());
            updated.put(name, new OrchardConfig.Target(newServer, newCultivator));
            OrchardConfig newConfig = new OrchardConfig(config.active(), updated);

            try {
                ConfigLoader.save(newConfig);
            } catch (IOException e) {
                System.err.println("Failed to save config: " + e.getMessage());
                return 1;
            }

            if (server != null) System.out.println("Set server = " + server + " on target '" + name + "'");
            if (cultivatorId != null) System.out.println("Set cultivator = " + cultivatorId + " on target '" + name + "'");
            return 0;
        }
    }

    @Command(name = "init", description = "Initialize or migrate configuration")
    public static class Init implements Callable<Integer> {

        @ParentCommand
        ConfigCommand parent;

        @Override
        public Integer call() {
            try {
                if (Files.exists(ConfigLoader.tomlFile())) {
                    System.out.print("config.toml already exists. Overwrite? [y/N] ");
                    int response = System.in.read();
                    if (response != 'y' && response != 'Y') {
                        System.out.println("Cancelled.");
                        return 0;
                    }
                } else if (Files.exists(ConfigLoader.legacyFile())) {
                    System.out.print("Migrate existing config.properties to config.toml? [Y/n] ");
                    int response = System.in.read();
                    if (response != 'n' && response != 'N') {
                        return migrate();
                    }
                    System.out.print("Create a fresh config.toml instead? [y/N] ");
                    response = System.in.read();
                    if (response != 'y' && response != 'Y') {
                        System.out.println("Cancelled.");
                        return 0;
                    }
                }

                OrchardConfig config = OrchardConfig.withDefault();
                ConfigLoader.save(config);
                printCreated(config);
                return 0;

            } catch (IOException e) {
                System.err.println("Failed to create config: " + e.getMessage());
                return 1;
            }
        }

        private int migrate() throws IOException {
            Properties props = new Properties();
            props.load(Files.newBufferedReader(ConfigLoader.legacyFile()));
            var targets = new LinkedHashMap<String, OrchardConfig.Target>();
            targets.put("default", new OrchardConfig.Target(
                    props.getProperty("server"),
                    props.getProperty("cultivator")));
            OrchardConfig config = new OrchardConfig("default", targets);
            ConfigLoader.save(config);
            System.out.println();
            System.out.println("Migrated config.properties → config.toml");
            System.out.println("  Active target: default");
            System.out.println("  server     = " + props.getProperty("server"));
            System.out.println("  cultivator = " + props.getProperty("cultivator"));
            System.out.println();
            System.out.println("You can delete ~/.orchard/config.properties once you've verified the migration.");
            System.out.println();
            return 0;
        }

        private void printCreated(OrchardConfig config) {
            System.out.println();
            System.out.println("Created configuration at " + ConfigLoader.tomlFile());
            System.out.println("  Active target: " + config.active());
            config.targets().forEach((name, t) -> {
                System.out.println("  server     = " + t.server());
                System.out.println("  cultivator = " + t.cultivator());
            });
            System.out.println();
        }
    }
}

// Temporary stub — replaced in Task 4
@Command(name = "target", description = "Manage configuration targets")
class ConfigTargetCommand implements Callable<Integer> {
    @Override public Integer call() { return 0; }
}
