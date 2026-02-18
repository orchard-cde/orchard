import * as vscode from 'vscode';
import { OrchardClient } from '../api/OrchardClient';
import {
    GroveResponse,
    GroveState,
    SeedlingInfo,
    SeedlingState,
    FruitInfo,
    FruitState,
} from '../api/types';

/**
 * A tree item representing a grove, seedling, or fruit in the sidebar.
 */
export class GroveTreeItem extends vscode.TreeItem {
    constructor(
        label: string,
        collapsibleState: vscode.TreeItemCollapsibleState,
        public readonly groveId?: string,
        public readonly itemType: 'grove' | 'seedling' | 'fruit' = 'grove'
    ) {
        super(label, collapsibleState);
    }
}

/**
 * Tree data provider for the Orchard Groves sidebar view.
 *
 * Displays groves as top-level items with seedling and fruit(s) as children.
 * Each item shows a state icon reflecting its current lifecycle phase.
 */
export class GroveTreeProvider implements vscode.TreeDataProvider<GroveTreeItem> {
    private _onDidChangeTreeData = new vscode.EventEmitter<GroveTreeItem | undefined | void>();
    readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

    private groves: GroveResponse[] = [];

    constructor(private readonly client: OrchardClient) {}

    /**
     * Refreshes the tree by re-fetching groves from the server.
     */
    refresh(): void {
        this._onDidChangeTreeData.fire();
    }

    getTreeItem(element: GroveTreeItem): vscode.TreeItem {
        return element;
    }

    async getChildren(element?: GroveTreeItem): Promise<GroveTreeItem[]> {
        if (!element) {
            // Root level: fetch and display all groves
            return this.getRootItems();
        }

        if (element.itemType === 'grove' && element.groveId) {
            // Grove children: seedling + fruits
            return this.getGroveChildren(element.groveId);
        }

        return [];
    }

    private async getRootItems(): Promise<GroveTreeItem[]> {
        try {
            this.groves = await this.client.listGroves();
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            vscode.window.showErrorMessage(`Failed to fetch groves: ${message}`);
            this.groves = [];
        }

        if (this.groves.length === 0) {
            const item = new GroveTreeItem(
                'No groves found. Plant one to get started!',
                vscode.TreeItemCollapsibleState.None
            );
            item.contextValue = 'empty';
            return [item];
        }

        return this.groves.map((grove) => {
            const item = new GroveTreeItem(
                `${grove.name} [${grove.state}]`,
                vscode.TreeItemCollapsibleState.Collapsed,
                grove.id,
                'grove'
            );

            item.iconPath = new vscode.ThemeIcon(
                groveStateIcon(grove.state),
                groveStateColor(grove.state)
            );

            item.tooltip = new vscode.MarkdownString(
                `**${grove.name}**\n\n` +
                `Repository: ${grove.repositoryUrl}\n\n` +
                `Branch: ${grove.branch}\n\n` +
                `State: ${grove.state}\n\n` +
                `Planted: ${new Date(grove.plantedAt).toLocaleString()}`
            );

            item.contextValue = grove.state === 'FLOURISHING' ? 'groveReady' : 'grove';

            return item;
        });
    }

    private getGroveChildren(groveId: string): GroveTreeItem[] {
        const grove = this.groves.find((g) => g.id === groveId);
        if (!grove) {
            return [];
        }

        const children: GroveTreeItem[] = [];

        // Seedling (VM) info
        if (grove.seedling) {
            const seedling = grove.seedling;
            const seedlingItem = new GroveTreeItem(
                `Seedling: ${seedling.state}`,
                vscode.TreeItemCollapsibleState.None,
                groveId,
                'seedling'
            );

            seedlingItem.iconPath = new vscode.ThemeIcon(
                seedlingStateIcon(seedling.state),
                seedlingStateColor(seedling.state)
            );

            seedlingItem.tooltip = new vscode.MarkdownString(
                `**Seedling (VM)**\n\n` +
                `State: ${seedling.state}\n\n` +
                `IP: ${seedling.ipAddress ?? 'pending'}\n\n` +
                `SSH Port: ${seedling.sshPort}\n\n` +
                `CPU: ${seedling.cpuCores} cores\n\n` +
                `Memory: ${seedling.memoryMb} MB\n\n` +
                `Disk: ${seedling.diskGb} GB`
            );

            children.push(seedlingItem);
        }

        // Fruits (devcontainers)
        if (grove.fruits && grove.fruits.length > 0) {
            for (const fruit of grove.fruits) {
                const label = fruit.serviceName
                    ? `Fruit: ${fruit.serviceName} [${fruit.state}]`
                    : `Fruit: ${fruit.state}`;

                const fruitItem = new GroveTreeItem(
                    label,
                    vscode.TreeItemCollapsibleState.None,
                    groveId,
                    'fruit'
                );

                fruitItem.iconPath = new vscode.ThemeIcon(
                    fruitStateIcon(fruit.state),
                    fruitStateColor(fruit.state)
                );

                fruitItem.tooltip = new vscode.MarkdownString(
                    `**Fruit (Container)**\n\n` +
                    `State: ${fruit.state}\n\n` +
                    `Container: ${fruit.containerName ?? 'pending'}\n\n` +
                    (fruit.serviceName ? `Service: ${fruit.serviceName}\n\n` : '')
                );

                children.push(fruitItem);
            }
        }

        // Repository info
        if (grove.repositoryUrl) {
            const repoItem = new GroveTreeItem(
                `Repo: ${grove.repositoryUrl}`,
                vscode.TreeItemCollapsibleState.None,
                groveId
            );
            repoItem.iconPath = new vscode.ThemeIcon('repo');
            repoItem.tooltip = `Branch: ${grove.branch}`;
            children.push(repoItem);
        }

        return children;
    }
}

// ---- State icon and color helpers ----

function groveStateIcon(state: GroveState): string {
    switch (state) {
        case 'PREPARING': return 'loading~spin';
        case 'PLANTING': return 'loading~spin';
        case 'GROWING': return 'loading~spin';
        case 'FLOURISHING': return 'check';
        case 'BLIGHTED': return 'error';
        case 'CLEARING': return 'loading~spin';
        case 'CLEARED': return 'circle-slash';
    }
}

function groveStateColor(state: GroveState): vscode.ThemeColor | undefined {
    switch (state) {
        case 'FLOURISHING': return new vscode.ThemeColor('testing.iconPassed');
        case 'BLIGHTED': return new vscode.ThemeColor('testing.iconFailed');
        default: return undefined;
    }
}

function seedlingStateIcon(state: SeedlingState): string {
    switch (state) {
        case 'GERMINATING': return 'loading~spin';
        case 'SPROUTING': return 'loading~spin';
        case 'SAPLING': return 'vm-running';
        case 'BLIGHTED': return 'error';
    }
}

function seedlingStateColor(state: SeedlingState): vscode.ThemeColor | undefined {
    switch (state) {
        case 'SAPLING': return new vscode.ThemeColor('testing.iconPassed');
        case 'BLIGHTED': return new vscode.ThemeColor('testing.iconFailed');
        default: return undefined;
    }
}

function fruitStateIcon(state: FruitState): string {
    switch (state) {
        case 'BUDDING': return 'loading~spin';
        case 'RIPENING': return 'loading~spin';
        case 'RIPE': return 'package';
        case 'ROTTED': return 'error';
    }
}

function fruitStateColor(state: FruitState): vscode.ThemeColor | undefined {
    switch (state) {
        case 'RIPE': return new vscode.ThemeColor('testing.iconPassed');
        case 'ROTTED': return new vscode.ThemeColor('testing.iconFailed');
        default: return undefined;
    }
}
