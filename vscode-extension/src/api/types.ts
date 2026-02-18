/**
 * TypeScript interfaces matching the Orchard Java API DTOs.
 *
 * These types mirror the records in dev.orchard.api.dto and
 * dev.orchard.core.model, providing type-safe access to the
 * Orchard REST API from the VS Code extension.
 */

/** Possible states for a Grove (workspace). */
export type GroveState =
    | 'PREPARING'
    | 'PLANTING'
    | 'GROWING'
    | 'FLOURISHING'
    | 'BLIGHTED'
    | 'CLEARING'
    | 'CLEARED';

/** Possible states for a Seedling (VM). */
export type SeedlingState =
    | 'GERMINATING'
    | 'SPROUTING'
    | 'SAPLING'
    | 'BLIGHTED';

/** Possible states for a Fruit (devcontainer). */
export type FruitState =
    | 'BUDDING'
    | 'RIPENING'
    | 'RIPE'
    | 'ROTTED';

/** Information about a Seedling (VM) within a Grove. */
export interface SeedlingInfo {
    id: string;
    state: SeedlingState;
    ipAddress: string | null;
    sshPort: number;
    cpuCores: number;
    memoryMb: number;
    diskGb: number;
}

/** Information about a Fruit (devcontainer) within a Grove. */
export interface FruitInfo {
    id: string;
    state: FruitState;
    containerId: string | null;
    containerName: string | null;
    serviceName: string | null;
}

/** A Seed is the devcontainer specification (blueprint for Fruit). */
export interface SeedInfo {
    image: string | null;
    ports: string[];
    envVars: Record<string, string>;
}

/** Full response for a Grove (workspace). */
export interface GroveResponse {
    id: string;
    name: string;
    repositoryUrl: string;
    branch: string;
    commitSha: string | null;
    state: GroveState;
    sshConnectionString: string | null;
    seedling: SeedlingInfo | null;
    fruits: FruitInfo[];
    plantedAt: string;
    lastAccessedAt: string | null;
}

/** Response for the authenticated cultivator (user). */
export interface CultivatorResponse {
    id: string;
    username: string;
    email: string | null;
    provider: string | null;
    avatarUrl: string | null;
    displayName: string | null;
    createdAt: string;
    lastActiveAt: string | null;
}

/** Request to plant (create) a new Grove. */
export interface PlantGroveRequest {
    name?: string;
    repositoryUrl: string;
    branch?: string;
    machineSize?: string;
}

/** Response for an OpenRewrite recipe. */
export interface RecipeResponse {
    id: string;
    name: string;
    description: string | null;
    category: string | null;
    tags: string[];
    options: RecipeOptionResponse[];
}

/** An option for an OpenRewrite recipe. */
export interface RecipeOptionResponse {
    name: string;
    type: string;
    description: string | null;
    required: boolean;
    defaultValue: string | null;
}
