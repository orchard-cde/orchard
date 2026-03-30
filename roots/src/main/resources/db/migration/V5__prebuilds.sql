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

-- Index for finding RIPE prebuilds by repo+branch (uniqueness enforced at application layer)
CREATE INDEX idx_prebuilds_state_repo_branch
    ON prebuilds (state, repository_url, branch);

-- Index for looking up prebuilds by repo and branch
CREATE INDEX idx_prebuilds_repo_branch
    ON prebuilds (repository_url, branch);
