package dev.orchard.nursery.gcp;

import java.util.Map;

/**
 * Configuration for the GCP Compute Engine seedling provider.
 *
 * @param project            GCP project ID
 * @param zone               GCP zone (e.g., "us-central1-a")
 * @param machineTypeMapping Maps CPU core count to GCP machine types (e.g., 2 -> "e2-standard-2")
 * @param imageFamily        Image family for the base VM image (e.g., "ubuntu-2204-lts")
 * @param imageProject       GCP project hosting the image family (e.g., "ubuntu-os-cloud")
 */
public record ComputeConfig(
    String project,
    String zone,
    Map<Integer, String> machineTypeMapping,
    String imageFamily,
    String imageProject
) {
    /**
     * Resolves the GCP machine type for a given CPU core count.
     * Falls back to e2-standard-2 if no mapping exists.
     */
    public String resolveMachineType(int cpuCores) {
        return machineTypeMapping.getOrDefault(cpuCores, "e2-standard-2");
    }

    /**
     * Returns the fully-qualified machine type URL.
     */
    public String machineTypeUrl(int cpuCores) {
        return String.format("zones/%s/machineTypes/%s", zone, resolveMachineType(cpuCores));
    }

    /**
     * Returns the source image URL for the configured image family.
     */
    public String sourceImageUrl() {
        return String.format("projects/%s/global/images/family/%s", imageProject, imageFamily);
    }
}
