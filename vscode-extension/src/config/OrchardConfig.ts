import * as vscode from 'vscode';

/**
 * Typed accessor for the Orchard VS Code extension configuration.
 *
 * Reads values from the "orchard" section of VS Code workspace settings
 * and provides an onChange listener for dynamic reconfiguration.
 */
export class OrchardConfig {
    private readonly disposables: vscode.Disposable[] = [];

    private _serverUrl: string;
    private _token: string;

    constructor() {
        const config = vscode.workspace.getConfiguration('orchard');
        this._serverUrl = config.get<string>('serverUrl', 'http://localhost:8080');
        this._token = config.get<string>('token', '');
    }

    /** The Orchard server base URL. */
    get serverUrl(): string {
        return this._serverUrl;
    }

    /** The authentication token (Bearer token). */
    get token(): string {
        return this._token;
    }

    /**
     * Registers a callback that fires when any Orchard configuration changes.
     * Returns a disposable that can be used to unregister the listener.
     */
    onChange(callback: (config: OrchardConfig) => void): vscode.Disposable {
        const disposable = vscode.workspace.onDidChangeConfiguration((event) => {
            if (event.affectsConfiguration('orchard')) {
                const config = vscode.workspace.getConfiguration('orchard');
                this._serverUrl = config.get<string>('serverUrl', 'http://localhost:8080');
                this._token = config.get<string>('token', '');
                callback(this);
            }
        });

        this.disposables.push(disposable);
        return disposable;
    }

    /**
     * Disposes all registered listeners.
     */
    dispose(): void {
        for (const disposable of this.disposables) {
            disposable.dispose();
        }
        this.disposables.length = 0;
    }
}
