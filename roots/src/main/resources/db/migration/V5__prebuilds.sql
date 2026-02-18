-- Prebuilds table: cached workspace images grown in the Greenhouse
CREATE TABLE prebuilds (
    id              UUID PRIMARY KEY,
    repository_url  VARCHAR(1024) NOT NULL,
    branch          VARCHAR(255) NOT NULL,
    commit_sha      VARCHAR(64),
    image_ref       VARCHAR(1024),
    state           VARCHAR(50) NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    completed_at    TIMESTAMP
);

-- Only one RIPE prebuild per repository+branch combination
CREATE UNIQUE INDEX idx_prebuilds_ripe_repo_branch
    ON prebuilds (repository_url, branch) WHERE state = 'RIPE';

-- Index for looking up prebuilds by repo and branch
CREATE INDEX idx_prebuilds_repo_branch
    ON prebuilds (repository_url, branch);
