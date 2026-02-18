-- Cultivators table (users who tend to groves)
CREATE TABLE cultivators (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_active_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_cultivators_username ON cultivators(username);
CREATE INDEX idx_cultivators_email ON cultivators(email);

-- Groves table (workspaces)
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

    -- Seedling (VM) embedded fields
    seedling_id UUID,
    seedling_provider_instance_id VARCHAR(255),
    seedling_ip_address VARCHAR(45),
    seedling_ssh_port INTEGER,
    seedling_state VARCHAR(50),
    seedling_cpu_cores INTEGER,
    seedling_memory_mb INTEGER,
    seedling_disk_gb INTEGER,

    -- Fruit (container) embedded fields
    fruit_id UUID,
    fruit_container_id VARCHAR(255),
    fruit_container_name VARCHAR(255),
    fruit_state VARCHAR(50),
    seed_json TEXT
);

CREATE INDEX idx_groves_cultivator ON groves(cultivator_id);
CREATE INDEX idx_groves_state ON groves(state);
CREATE INDEX idx_groves_cultivator_state ON groves(cultivator_id, state);
