package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.client.OrchardClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

@Command(
    name = "status",
    description = "Check Orchard server status"
)
public class StatusCommand implements Callable<Integer> {

    @ParentCommand
    Trowel parent;

    @Override
    public Integer call() {
        try {
            OrchardClient client = new OrchardClient(parent.getServerUrl(), parent.getCultivatorId());
            var health = client.checkHealth();

            System.out.println();
            System.out.println("Orchard Server Status");
            System.out.println("-".repeat(30));
            System.out.println("  Server:  " + parent.getServerUrl());
            System.out.println("  Status:  " + (health.status().equals("healthy") ? "\u2705 " : "\u274C ") + health.status());
            System.out.println("  Name:    " + health.name());
            System.out.println("  Version: " + health.version());
            System.out.println();

            return 0;
        } catch (Exception e) {
            System.out.println();
            System.out.println("Orchard Server Status");
            System.out.println("-".repeat(30));
            System.out.println("  Server:  " + parent.getServerUrl());
            System.out.println("  Status:  \u274C unreachable");
            System.out.println("  Error:   " + e.getMessage());
            System.out.println();
            return 1;
        }
    }
}
