import * as vscode from 'vscode';
import { OrchardClient } from '../api/OrchardClient';

/**
 * Clears (tears down) a grove after user confirmation.
 *
 * This destroys the VM and all containers associated with the grove.
 * A confirmation dialog is shown before proceeding.
 */
export async function clearGrove(client: OrchardClient, groveId: string): Promise<void> {
    // Show confirmation dialog
    const confirmation = await vscode.window.showWarningMessage(
        'Are you sure you want to clear this grove? This will destroy the VM and all containers.',
        { modal: true },
        'Clear Grove'
    );

    if (confirmation !== 'Clear Grove') {
        return; // User cancelled
    }

    try {
        await client.clearGrove(groveId);
        vscode.window.showInformationMessage('Grove cleared successfully.');
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        vscode.window.showErrorMessage(`Failed to clear grove: ${message}`);
    }
}
