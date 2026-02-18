ALTER TABLE cultivators ADD COLUMN provider VARCHAR(50) NOT NULL DEFAULT 'oidc';
ALTER TABLE cultivators ADD COLUMN provider_id VARCHAR(255);
ALTER TABLE cultivators ADD COLUMN avatar_url VARCHAR(1024);
ALTER TABLE cultivators ADD COLUMN display_name VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS idx_cultivators_provider ON cultivators(provider, provider_id);
