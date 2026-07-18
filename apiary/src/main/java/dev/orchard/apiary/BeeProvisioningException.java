package dev.orchard.apiary;

/**
 * Raised when a Bee's provisioning step (binary install, config write, process start)
 * fails before the Bee reaches BUZZING.
 */
public class BeeProvisioningException extends RuntimeException {

    public BeeProvisioningException(String msg) {
        super(msg);
    }

    public BeeProvisioningException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
