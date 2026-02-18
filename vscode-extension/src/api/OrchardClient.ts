import {
    GroveResponse,
    PlantGroveRequest,
    CultivatorResponse,
    RecipeResponse,
} from './types';

/**
 * HTTP client for the Orchard CDE REST API.
 *
 * Uses the native fetch API (available in Node 18+) to communicate
 * with the Orchard server. All methods throw on non-OK responses.
 */
export class OrchardClient {
    private serverUrl: string;
    private token: string;

    constructor(serverUrl: string, token: string) {
        // Strip trailing slash for consistent URL construction
        this.serverUrl = serverUrl.replace(/\/+$/, '');
        this.token = token;
    }

    /**
     * Updates the authentication token (e.g., after config change).
     */
    updateToken(token: string): void {
        this.token = token;
    }

    /**
     * Updates the server URL.
     */
    updateServerUrl(serverUrl: string): void {
        this.serverUrl = serverUrl.replace(/\/+$/, '');
    }

    /**
     * Lists all groves for the authenticated cultivator.
     */
    async listGroves(): Promise<GroveResponse[]> {
        return this.request<GroveResponse[]>('GET', '/api/groves');
    }

    /**
     * Gets a single grove by ID.
     */
    async getGrove(id: string): Promise<GroveResponse> {
        return this.request<GroveResponse>('GET', `/api/groves/${id}`);
    }

    /**
     * Plants (creates) a new grove.
     */
    async plantGrove(request: PlantGroveRequest): Promise<GroveResponse> {
        return this.request<GroveResponse>('POST', '/api/groves', request);
    }

    /**
     * Clears (deletes) a grove, tearing down its VM and containers.
     */
    async clearGrove(id: string): Promise<void> {
        await this.request<void>('DELETE', `/api/groves/${id}`);
    }

    /**
     * Gets the authenticated cultivator's profile.
     */
    async getMe(): Promise<CultivatorResponse> {
        return this.request<CultivatorResponse>('GET', '/api/me');
    }

    /**
     * Lists available OpenRewrite recipes.
     */
    async listRecipes(): Promise<RecipeResponse[]> {
        return this.request<RecipeResponse[]>('GET', '/api/recipes');
    }

    /**
     * Gets the SSH config block for connecting to a grove's seedling.
     * Returns raw text suitable for writing to ~/.ssh/config.
     */
    async getSshConfig(groveId: string): Promise<string> {
        const url = `${this.serverUrl}/api/groves/${groveId}/ssh-config`;
        const response = await fetch(url, {
            method: 'GET',
            headers: this.buildHeaders('text/plain'),
        });

        if (!response.ok) {
            const body = await response.text().catch(() => '');
            throw new OrchardApiError(
                `GET ${url} failed: ${response.status} ${response.statusText}`,
                response.status,
                body
            );
        }

        return response.text();
    }

    // ---- Private helpers ----

    private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
        const url = `${this.serverUrl}${path}`;
        const options: RequestInit = {
            method,
            headers: this.buildHeaders(),
        };

        if (body !== undefined) {
            options.body = JSON.stringify(body);
        }

        const response = await fetch(url, options);

        if (!response.ok) {
            const errorBody = await response.text().catch(() => '');
            throw new OrchardApiError(
                `${method} ${url} failed: ${response.status} ${response.statusText}`,
                response.status,
                errorBody
            );
        }

        // 204 No Content returns void
        if (response.status === 204) {
            return undefined as T;
        }

        return response.json() as Promise<T>;
    }

    private buildHeaders(accept?: string): Record<string, string> {
        const headers: Record<string, string> = {
            'Content-Type': 'application/json',
            'Accept': accept ?? 'application/json',
        };

        if (this.token) {
            headers['Authorization'] = `Bearer ${this.token}`;
        }

        return headers;
    }
}

/**
 * Error thrown when the Orchard API returns a non-OK response.
 */
export class OrchardApiError extends Error {
    constructor(
        message: string,
        public readonly statusCode: number,
        public readonly responseBody: string
    ) {
        super(message);
        this.name = 'OrchardApiError';
    }
}
