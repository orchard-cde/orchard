-- Existing grove rows are disposable (D1): CLEARED groves are dropped, anything else refuses the upgrade.
DELETE FROM bees WHERE grove_id IN (SELECT id FROM groves WHERE state = 'CLEARED');

DELETE FROM groves WHERE state = 'CLEARED';

DO $$
DECLARE
    remaining BIGINT;
BEGIN
    SELECT count(*) INTO remaining FROM groves;
    IF remaining > 0 THEN
        RAISE EXCEPTION 'V10: % groves remain; clear them (and confirm no ORPHANED substrate) before upgrading', remaining;
    END IF;
END
$$;

ALTER TABLE groves
    DROP COLUMN seedling_id,
    DROP COLUMN seedling_provider_instance_id,
    DROP COLUMN seedling_ip_address,
    DROP COLUMN seedling_ssh_port,
    DROP COLUMN seedling_state,
    DROP COLUMN seedling_cpu_cores,
    DROP COLUMN seedling_memory_mb,
    DROP COLUMN seedling_disk_gb,
    DROP COLUMN seedling_provider_id;

ALTER TABLE groves ADD COLUMN plot_json TEXT NOT NULL;
