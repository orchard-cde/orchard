CREATE TABLE ssh_public_keys (
    id UUID PRIMARY KEY,
    cultivator_id UUID NOT NULL REFERENCES cultivators(id),
    name VARCHAR(255) NOT NULL,
    public_key TEXT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_ssh_public_keys_cultivator ON ssh_public_keys(cultivator_id);
