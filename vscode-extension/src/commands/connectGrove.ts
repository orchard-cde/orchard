import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { OrchardClient } from '../api/OrchardClient';

/**
 * Connects to a grove via SSH by:
 *   1. Fetching the SSH config block from the Orchard API
 *   2. Writing it to an orchard-specific SSH config file
 *   3. Opening a Remote-SSH connection in VS Code
 */
export async function connectGrove(client: OrchardClient, groveId: string): Promise<void> {
    try {
        // Fetch SSH config from the server
        const sshConfig = await client.getSshConfig(groveId);

        if (!sshConfig || sshConfig.trim().length === 0) {
            vscode.window.showWarningMessage(
                'Grove is not ready for SSH connections yet. Wait until the seedling reaches SAPLING state.'
            );
            return;
        }

        // Write to an Orchard-specific SSH config file
        const sshDir = path.join(os.homedir(), '.ssh');
        const orchardConfigPath = path.join(sshDir, 'orchard_config');

        // Ensure .ssh directory exists
        if (!fs.existsSync(sshDir)) {
            fs.mkdirSync(sshDir, { mode: 0o700 });
        }

        // Read existing orchard config to update or append
        let existingConfig = '';
        if (fs.existsSync(orchardConfigPath)) {
            existingConfig = fs.readFileSync(orchardConfigPath, 'utf-8');
        }

        // Extract the Host name from the new config
        const hostMatch = sshConfig.match(/^Host\s+(.+)$/m);
        const hostName = hostMatch ? hostMatch[1].trim() : null;

        if (hostName) {
            // Remove any existing entry for this host
            const hostPattern = new RegExp(
                `# Orchard Grove: [^\n]*\nHost ${escapeRegex(hostName)}\n(?:  [^\n]*\n)*`,
                'g'
            );
            existingConfig = existingConfig.replace(hostPattern, '');
        }

        // Append the new config entry
        const updatedConfig = existingConfig.trimEnd() + '\n\n' + sshConfig.trim() + '\n';
        fs.writeFileSync(orchardConfigPath, updatedConfig, { mode: 0o600 });

        // Ensure the main SSH config includes our orchard config
        ensureInclude(sshDir, orchardConfigPath);

        if (hostName) {
            // Open Remote-SSH connection
            const remoteUri = vscode.Uri.parse(`vscode-remote://ssh-remote+${hostName}/workspace`);
            await vscode.commands.executeCommand('vscode.openFolder', remoteUri, {
                forceNewWindow: true,
            });
        } else {
            vscode.window.showWarningMessage(
                'Could not parse SSH host name from config. SSH config was saved but automatic connection could not be started.'
            );
        }
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        vscode.window.showErrorMessage(`Failed to connect to grove: ${message}`);
    }
}

/**
 * Ensures the main SSH config file includes the orchard config.
 */
function ensureInclude(sshDir: string, orchardConfigPath: string): void {
    const mainConfigPath = path.join(sshDir, 'config');
    const includeLine = `Include ${orchardConfigPath}`;

    let mainConfig = '';
    if (fs.existsSync(mainConfigPath)) {
        mainConfig = fs.readFileSync(mainConfigPath, 'utf-8');
    }

    if (!mainConfig.includes(includeLine)) {
        // Add Include directive at the top (SSH uses first match)
        const updated = includeLine + '\n\n' + mainConfig;
        fs.writeFileSync(mainConfigPath, updated, { mode: 0o600 });
    }
}

/**
 * Escapes special regex characters in a string.
 */
function escapeRegex(str: string): string {
    return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
