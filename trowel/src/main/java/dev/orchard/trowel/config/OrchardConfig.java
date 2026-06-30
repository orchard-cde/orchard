package dev.orchard.trowel.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record OrchardConfig(String active, Map<String, Target> targets) {

    public record Target(String server, String cultivator) {}

    public Target activeTarget() {
        if (targets == null || active == null) {
            return null;
        }
        return targets.get(active);
    }

    public static OrchardConfig withDefault() {
        var targets = new LinkedHashMap<String, Target>();
        targets.put("local", new Target("http://localhost:7778", UUID.randomUUID().toString()));
        return new OrchardConfig("local", targets);
    }
}
