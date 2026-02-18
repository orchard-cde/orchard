-- Create fruits table for multi-container workspace support
CREATE TABLE fruits (
    id UUID PRIMARY KEY,
    grove_id UUID NOT NULL REFERENCES groves(id) ON DELETE CASCADE,
    seedling_id UUID,
    container_id VARCHAR(255),
    container_name VARCHAR(255),
    service_name VARCHAR(255),
    state VARCHAR(50) NOT NULL,
    seed_json TEXT,
    budded_at TIMESTAMP WITH TIME ZONE,
    ripened_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_fruits_grove ON fruits(grove_id);
CREATE INDEX idx_fruits_grove_state ON fruits(grove_id, state);

-- Migrate existing embedded fruit data from groves to fruits table
INSERT INTO fruits (id, grove_id, seedling_id, container_id, container_name, service_name, state, seed_json, budded_at, ripened_at)
SELECT
    fruit_id,
    id,
    seedling_id,
    fruit_container_id,
    fruit_container_name,
    NULL,
    fruit_state,
    seed_json,
    planted_at,
    NULL
FROM groves
WHERE fruit_id IS NOT NULL;

-- Drop old fruit columns from groves table
ALTER TABLE groves DROP COLUMN IF EXISTS fruit_id;
ALTER TABLE groves DROP COLUMN IF EXISTS fruit_container_id;
ALTER TABLE groves DROP COLUMN IF EXISTS fruit_container_name;
ALTER TABLE groves DROP COLUMN IF EXISTS fruit_state;
ALTER TABLE groves DROP COLUMN IF EXISTS seed_json;
