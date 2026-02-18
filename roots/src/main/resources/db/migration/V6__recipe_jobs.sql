-- Recipe application jobs: tracks OpenRewrite recipe applications to Groves
CREATE TABLE recipe_jobs (
    id              UUID PRIMARY KEY,
    grove_id        UUID NOT NULL REFERENCES groves(id),
    recipe_id       VARCHAR(512) NOT NULL,
    state           VARCHAR(50) NOT NULL,
    changed_files   TEXT,
    diff            TEXT,
    created_at      TIMESTAMP NOT NULL,
    completed_at    TIMESTAMP
);

-- Index for looking up jobs by grove
CREATE INDEX idx_recipe_jobs_grove_id ON recipe_jobs (grove_id);
