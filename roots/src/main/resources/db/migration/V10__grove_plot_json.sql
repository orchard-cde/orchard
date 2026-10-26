-- Refuses (fails on the NOT NULL add) while non-CLEARED groves exist; must stay portable across PostgreSQL and H2 (the native dev-server runs it on H2).
DELETE FROM bees WHERE grove_id IN (SELECT id FROM groves WHERE state = 'CLEARED');

DELETE FROM groves WHERE state = 'CLEARED';

ALTER TABLE groves ADD COLUMN plot_json TEXT NOT NULL;

ALTER TABLE groves DROP COLUMN seedling_id;
ALTER TABLE groves DROP COLUMN seedling_provider_instance_id;
ALTER TABLE groves DROP COLUMN seedling_ip_address;
ALTER TABLE groves DROP COLUMN seedling_ssh_port;
ALTER TABLE groves DROP COLUMN seedling_state;
ALTER TABLE groves DROP COLUMN seedling_cpu_cores;
ALTER TABLE groves DROP COLUMN seedling_memory_mb;
ALTER TABLE groves DROP COLUMN seedling_disk_gb;
ALTER TABLE groves DROP COLUMN seedling_provider_id;
