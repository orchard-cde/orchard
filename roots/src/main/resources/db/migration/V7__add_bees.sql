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

CREATE INDEX idx_bees_grove_id ON bees(grove_id);
CREATE INDEX idx_bees_grove_id_state ON bees(grove_id, state);
