package dev.orchard.trowel;

import dev.orchard.trowel.command.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Callable;

@Command(
    name = "trowel",
    mixinStandardHelpOptions = true,
    version = "Trowel 0.1.0 - The Orchard Planting Tool",
    description = "Cultivate your cloud development environments",
    subcommands = {
        GroveCommand.class,
        ConfigCommand.class,
        StatusCommand.class,
        DevServerCommand.class,
        CommandLine.HelpCommand.class
    },
    header = {
        "",
        "@|green  _____ ____   _____        _______ _     |@",
        "@|green |_   _|  _ \\ / _ \\ \\      / / ____| |    |@",
        "@|green   | | | |_) | | | \\ \\ /\\ / /|  _| | |    |@",
        "@|green   | | |  _ <| |_| |\\ V  V / | |___| |___ |@",
        "@|green   |_| |_| \\_\\\\___/  \\_/\\_/  |_____|_____|@",
        "",
        "@|yellow        The Orchard Planting Tool|@",
        ""
    }
)
public class Trowel implements Callable<Integer> {

    @Option(names = {"-s", "--server"}, description = "Orchard server URL")
    private String serverUrl;

    @Option(names = {"--cultivator"}, description = "Cultivator ID")
    private String cultivatorId;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Trowel())
            .setColorScheme(createColorScheme())
            .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    public String getServerUrl() {
        if (serverUrl != null) return serverUrl;
        String env = System.getenv("ORCHARD_SERVER_URL");
        if (env != null) return env;
        String config = loadConfigProperty("server");
        if (config != null) return config;
        return "http://localhost:8080";
    }

    public String getCultivatorId() {
        if (cultivatorId != null) return cultivatorId;
        String env = System.getenv("ORCHARD_CULTIVATOR_ID");
        if (env != null) return env;
        return loadConfigProperty("cultivator");
    }

    private String loadConfigProperty(String key) {
        Path configFile = Path.of(System.getProperty("user.home"), ".orchard", "config.properties");
        if (Files.exists(configFile)) {
            try (var reader = Files.newBufferedReader(configFile)) {
                Properties props = new Properties();
                props.load(reader);
                return props.getProperty(key);
            } catch (IOException e) {
                // ignore unreadable config
            }
        }
        return null;
    }

    private static CommandLine.Help.ColorScheme createColorScheme() {
        return new CommandLine.Help.ColorScheme.Builder()
            .commands(CommandLine.Help.Ansi.Style.bold, CommandLine.Help.Ansi.Style.fg_green)
            .options(CommandLine.Help.Ansi.Style.fg_yellow)
            .parameters(CommandLine.Help.Ansi.Style.fg_cyan)
            .optionParams(CommandLine.Help.Ansi.Style.italic)
            .build();
    }
}
