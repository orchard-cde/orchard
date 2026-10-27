CREATE TABLE cultivators (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_active_at TIMESTAMP WITH TIME ZONE NOT NULL,
    provider VARCHAR(50) NOT NULL DEFAULT 'oidc',
    provider_id VARCHAR(255),
    avatar_url VARCHAR(1024),
    display_name VARCHAR(255)
);

CREATE UNIQUE INDEX idx_cultivators_provider ON cultivators(provider, provider_id);

CREATE TABLE groves (
    id UUID PRIMARY KEY,
    cultivator_id UUID NOT NULL REFERENCES cultivators(id),
    name VARCHAR(255) NOT NULL,
    repository_url VARCHAR(1024) NOT NULL,
    branch VARCHAR(255) NOT NULL,
    commit_sha VARCHAR(40),
    state VARCHAR(50) NOT NULL,
    planted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_accessed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    seed_spec VARCHAR(20) DEFAULT 'AUTO',
    plot_json TEXT NOT NULL
);

CREATE INDEX idx_groves_state ON groves(state);
CREATE INDEX idx_groves_cultivator_state ON groves(cultivator_id, state);

CREATE TABLE fruits (
    id UUID PRIMARY KEY,
    grove_id UUID NOT NULL REFERENCES groves(id) ON DELETE CASCADE,
    plot_id UUID,
    container_id VARCHAR(255),
    container_name VARCHAR(255),
    service_name VARCHAR(255),
    state VARCHAR(50) NOT NULL,
    seed_json TEXT,
    budded_at TIMESTAMP WITH TIME ZONE,
    ripened_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_fruits_grove_state ON fruits(grove_id, state);

CREATE TABLE prebuilds (
    id UUID PRIMARY KEY,
    repository_url VARCHAR(1024) NOT NULL,
    branch VARCHAR(255) NOT NULL,
    commit_sha VARCHAR(64),
    image_ref VARCHAR(1024),
    state VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE INDEX idx_prebuilds_state_repo_branch ON prebuilds(state, repository_url, branch);
CREATE INDEX idx_prebuilds_repo_branch ON prebuilds(repository_url, branch);

CREATE TABLE bees (
    id UUID PRIMARY KEY,
    grove_id UUID NOT NULL REFERENCES groves(id),
    type VARCHAR(20) NOT NULL,
    state VARCHAR(20) NOT NULL,
    spec_type VARCHAR(20) NOT NULL,
    spec_version VARCHAR(50),
    process_id VARCHAR(100),
    hatched_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    stopped_at TIMESTAMP
);

CREATE INDEX idx_bees_grove_id_state ON bees(grove_id, state);

CREATE TABLE ssh_public_keys (
    id UUID PRIMARY KEY,
    cultivator_id UUID NOT NULL REFERENCES cultivators(id),
    name VARCHAR(255) NOT NULL,
    public_key TEXT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_ssh_public_keys_cultivator ON ssh_public_keys(cultivator_id);
