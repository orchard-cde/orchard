import * as vscode from 'vscode';
import { OrchardClient } from './api/OrchardClient';
import { OrchardConfig } from './config/OrchardConfig';
import { GroveTreeProvider, GroveTreeItem } from './providers/GroveTreeProvider';
import { plantGrove } from './commands/plantGrove';
import { connectGrove } from './commands/connectGrove';
import { clearGrove } from './commands/clearGrove';

/**
 * Activates the Orchard CDE extension.
 *
 * Sets up the API client, tree view provider, and registers all commands.
 */
export function activate(context: vscode.ExtensionContext): void {
    // Load configuration
    const config = new OrchardConfig();
    context.subscriptions.push(config);

    // Create API client
    const client = new OrchardClient(config.serverUrl, config.token);

    // Update client when configuration changes
    config.onChange((updated) => {
        client.updateServerUrl(updated.serverUrl);
        client.updateToken(updated.token);
        treeProvider.refresh();
    });

    // Register tree data provider for the Groves sidebar
    const treeProvider = new GroveTreeProvider(client);
    const treeView = vscode.window.createTreeView('orchardGroves', {
        treeDataProvider: treeProvider,
        showCollapseAll: true,
    });
    context.subscriptions.push(treeView);

    // Register commands
    context.subscriptions.push(
        vscode.commands.registerCommand('orchard.plantGrove', async () => {
            await plantGrove(client);
            treeProvider.refresh();
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('orchard.connectGrove', async (item?: GroveTreeItem) => {
            const groveId = item?.groveId ?? await promptForGroveId();
            if (groveId) {
                await connectGrove(client, groveId);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('orchard.clearGrove', async (item?: GroveTreeItem) => {
            const groveId = item?.groveId ?? await promptForGroveId();
            if (groveId) {
                await clearGrove(client, groveId);
                treeProvider.refresh();
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('orchard.refreshGroves', () => {
            treeProvider.refresh();
        })
    );
}

/**
 * Deactivates the extension. Cleanup is handled via disposables.
 */
export function deactivate(): void {
    // Disposables registered via context.subscriptions are cleaned up automatically.
}

/**
 * Prompts the user to enter a grove ID manually (fallback when no tree item is provided).
 */
async function promptForGroveId(): Promise<string | undefined> {
    return vscode.window.showInputBox({
        prompt: 'Enter the Grove ID',
        placeHolder: 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx',
    });
}
