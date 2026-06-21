package dev.orchard.trowel;

import dev.orchard.trowel.command.*;
import dev.orchard.trowel.config.ConfigLoader;
import dev.orchard.trowel.config.OrchardConfig;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(
    name = "trowel",
    mixinStandardHelpOptions = true,
    versionProvider = TrowelVersionProvider.class,
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

    @Option(names = {"-t", "--target"}, description = "Named config target to use (overrides active target in config)")
    private String targetName;

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
        OrchardConfig.Target target = resolveConfigTarget();
        if (target != null && target.server() != null) return target.server();
        return "http://localhost:7778";
    }

    public String getCultivatorId() {
        if (cultivatorId != null) return cultivatorId;
        String env = System.getenv("ORCHARD_CULTIVATOR_ID");
        if (env != null) return env;
        OrchardConfig.Target target = resolveConfigTarget();
        return target != null ? target.cultivator() : null;
    }

    /** Returns the --target flag value or ORCHARD_TARGET env var, or null if neither is set. */
    public String getTargetName() {
        if (targetName != null) return targetName;
        return System.getenv("ORCHARD_TARGET");
    }

    private OrchardConfig.Target resolveConfigTarget() {
        OrchardConfig config = ConfigLoader.load();
        if (config == null || config.targets() == null) return null;
        String name = getTargetName() != null ? getTargetName() : config.active();
        return name != null ? config.targets().get(name) : null;
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
