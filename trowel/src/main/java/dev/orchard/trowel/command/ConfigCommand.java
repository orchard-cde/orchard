package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Callable;

@Command(
    name = "config",
    description = "Configure the Trowel CLI",
    subcommands = {
        ConfigCommand.Show.class,
        ConfigCommand.Set.class,
        ConfigCommand.Init.class
    }
)
public class ConfigCommand implements Callable<Integer> {

    @ParentCommand
    Trowel parent;

    static Path configDir() {
        return Path.of(System.getProperty("user.home"), ".orchard");
    }

    static Path configFile() {
        return configDir().resolve("config.properties");
    }

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
            System.out.println("  Config file: " + configFile());
            System.out.println();

            if (Files.exists(configFile())) {
                try {
                    Properties props = new Properties();
                    props.load(Files.newBufferedReader(configFile()));
                    props.forEach((k, v) -> System.out.println("  " + k + " = " + v));
                } catch (IOException e) {
                    System.err.println("Failed to read config: " + e.getMessage());
                    return 1;
                }
            } else {
                System.out.println("  (no configuration file found)");
                System.out.println();
                System.out.println("  Run 'trowel config init' to create one.");
            }

            System.out.println();
            System.out.println("Current effective settings:");
            System.out.println("  server = " + parent.parent.getServerUrl());
            System.out.println("  cultivator = " + parent.parent.getCultivatorId());
            System.out.println();

            return 0;
        }
    }

    @Command(name = "set", description = "Set a configuration value")
    public static class Set implements Callable<Integer> {

        @ParentCommand
        ConfigCommand parent;

        @Option(names = "--server", description = "Orchard server URL")
        String server;

        @Option(names = "--cultivator", description = "Your cultivator ID")
        String cultivatorId;

        @Override
        public Integer call() {
            try {
                Files.createDirectories(configDir());

                Properties props = new Properties();
                if (Files.exists(configFile())) {
                    props.load(Files.newBufferedReader(configFile()));
                }

                if (server != null) {
                    props.setProperty("server", server);
                    System.out.println("Set server = " + server);
                }

                if (cultivatorId != null) {
                    props.setProperty("cultivator", cultivatorId);
                    System.out.println("Set cultivator = " + cultivatorId);
                }

                props.store(Files.newBufferedWriter(configFile()), "Trowel CLI Configuration");
                return 0;

            } catch (IOException e) {
                System.err.println("Failed to save config: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "init", description = "Initialize configuration with defaults")
    public static class Init implements Callable<Integer> {

        @ParentCommand
        ConfigCommand parent;

        @Override
        public Integer call() {
            try {
                if (Files.exists(configFile())) {
                    System.out.print("Configuration already exists. Overwrite? [y/N] ");
                    int response = System.in.read();
                    if (response != 'y' && response != 'Y') {
                        System.out.println("Cancelled.");
                        return 0;
                    }
                }

                Files.createDirectories(configDir());

                Properties props = new Properties();
                props.setProperty("server", "http://localhost:7778");
                props.setProperty("cultivator", java.util.UUID.randomUUID().toString());

                props.store(Files.newBufferedWriter(configFile()), "Trowel CLI Configuration");

                System.out.println();
                System.out.println("Created configuration at " + configFile());
                System.out.println();
                props.forEach((k, v) -> System.out.println("  " + k + " = " + v));
                System.out.println();

                return 0;

            } catch (IOException e) {
                System.err.println("Failed to create config: " + e.getMessage());
                return 1;
            }
        }
    }
}
