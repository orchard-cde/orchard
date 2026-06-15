package dev.orchard.nursery;

/**
 * Raised when a Seedling's provisioning step (cloud-init readiness, CLI install, etc.)
 * fails verification before READY. Distinct from runtime fruit-grow failures.
 */
public class SeedlingProvisioningException extends RuntimeException {
    public SeedlingProvisioningException(String msg) {
        super(msg);
    }

    public SeedlingProvisioningException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
