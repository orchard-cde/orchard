package dev.orchard.nursery.azure;

import java.util.Map;

/**
 * Configuration for the Azure VM seedling provider.
 *
 * @param subscriptionId Azure subscription ID
 * @param resourceGroup  Azure resource group for VM management
 * @param location       Azure region (e.g., "eastus")
 * @param vmSizeMapping  Maps CPU core count to Azure VM sizes (e.g., 2 -> "Standard_B2s")
 */
public record AzureConfig(
    String subscriptionId,
    String resourceGroup,
    String location,
    Map<Integer, String> vmSizeMapping
) {
    /**
     * Resolves the Azure VM size for a given CPU core count.
     * Falls back to Standard_B2s if no mapping exists.
     */
    public String resolveVmSize(int cpuCores) {
        return vmSizeMapping.getOrDefault(cpuCores, "Standard_B2s");
    }
}
