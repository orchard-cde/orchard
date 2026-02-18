import * as vscode from 'vscode';
import { OrchardClient } from '../api/OrchardClient';

/**
 * Multi-step command to plant (create) a new grove.
 *
 * Walks the user through:
 *   1. Entering a repository URL
 *   2. Choosing a branch (default: main)
 *   3. Naming the grove (auto-generated from repo name)
 *
 * Then calls the Orchard API to plant the grove.
 */
export async function plantGrove(client: OrchardClient): Promise<void> {
    // Step 1: Repository URL
    const repositoryUrl = await vscode.window.showInputBox({
        title: 'Plant Grove (1/3): Repository URL',
        prompt: 'Enter the git repository URL',
        placeHolder: 'https://github.com/owner/repo.git',
        validateInput: (value) => {
            if (!value || value.trim().length === 0) {
                return 'Repository URL is required';
            }
            return undefined;
        },
    });

    if (!repositoryUrl) {
        return; // User cancelled
    }

    // Step 2: Branch
    const branch = await vscode.window.showInputBox({
        title: 'Plant Grove (2/3): Branch',
        prompt: 'Enter the branch to clone',
        value: 'main',
        placeHolder: 'main',
    });

    if (branch === undefined) {
        return; // User cancelled
    }

    // Step 3: Grove name (auto-generated from repo)
    const defaultName = extractRepoName(repositoryUrl) + '-' + (branch || 'main');
    const name = await vscode.window.showInputBox({
        title: 'Plant Grove (3/3): Name',
        prompt: 'Enter a name for the grove',
        value: defaultName,
        placeHolder: defaultName,
    });

    if (name === undefined) {
        return; // User cancelled
    }

    // Plant the grove
    try {
        const grove = await client.plantGrove({
            repositoryUrl: repositoryUrl.trim(),
            branch: branch || 'main',
            name: name || defaultName,
        });

        vscode.window.showInformationMessage(
            `Grove "${grove.name}" planted successfully! State: ${grove.state}`
        );
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        vscode.window.showErrorMessage(`Failed to plant grove: ${message}`);
    }
}

/**
 * Extracts a human-readable name from a repository URL.
 * e.g., "https://github.com/owner/my-repo.git" -> "my-repo"
 */
function extractRepoName(repoUrl: string): string {
    let name = repoUrl;
    if (name.endsWith('.git')) {
        name = name.substring(0, name.length - 4);
    }
    const lastSlash = name.lastIndexOf('/');
    if (lastSlash >= 0) {
        name = name.substring(lastSlash + 1);
    }
    return name || 'grove';
}
