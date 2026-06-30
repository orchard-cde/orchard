package dev.orchard.trowel;

import picocli.CommandLine.IVersionProvider;

import java.util.Properties;

public class TrowelVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() throws Exception {
        var props = new Properties();
        try (var in = getClass().getResourceAsStream("/version.properties")) {
            if (in == null) {
                return new String[] { "Trowel (unknown)" };
            }
            props.load(in);
        }
        var version = props.getProperty("version", "unknown");
        return new String[] { "Trowel " + version + " - The Orchard Planting Tool" };
    }
}
