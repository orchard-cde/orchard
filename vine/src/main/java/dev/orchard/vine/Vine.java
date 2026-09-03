package dev.orchard.vine;

/**
 * How the control plane reaches into a grove to run something. One implementation per substrate:
 * SSH for VM-backed groves, {@code docker exec} for container-backed ones.
 *
 * <p>Obtained from the provider directly rather than through a route lookup. This is load-bearing:
 * provisioning-time and diagnostic exec both run before a grove is routable, so a readiness-gated
 * lookup could never resolve for those callers. See issue #215.
 *
 * <p>Interactive shell and file transfer are deliberately absent — no caller needs them until the
 * SSH gateway's relay is generalized.
 */
@FunctionalInterface
public interface Vine {

    /** The command channel for this substrate. */
    CommandRunner commands();
}
