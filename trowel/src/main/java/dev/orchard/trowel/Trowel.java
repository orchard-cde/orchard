package dev.orchard.trowel;

import dev.orchard.trowel.command.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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

    @Option(names = {"-s", "--server"}, description = "Orchard server URL", defaultValue = "http://localhost:8080")
    private String serverUrl;

    @Option(names = {"--cultivator"}, description = "Cultivator ID", defaultValue = "${ORCHARD_CULTIVATOR_ID}")
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
        return serverUrl;
    }

    public String getCultivatorId() {
        return cultivatorId;
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
