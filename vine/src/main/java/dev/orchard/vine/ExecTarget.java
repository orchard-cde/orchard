package dev.orchard.vine;

import java.util.UUID;

/**
 * Where to run something, and what to call it in logs. Substrate-neutral: a VM-backed provider
 * builds one from a seedling's endpoint, a container-backed one from a container handle, and
 * nothing downstream of here can tell the difference.
 *
 * @param runner        the command channel
 * @param workspacePath the absolute path the repository is checked out at on the target
 * @param targetId      the substrate's id — correlation only, never used to address anything
 */
public record ExecTarget(CommandRunner runner, String workspacePath, UUID targetId) {}
