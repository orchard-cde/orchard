-- Fruit's relation is to the substrate a grove occupies, not specifically to a VM.
-- Renamed ahead of the Plot model so this migration stays independent of it.
ALTER TABLE fruits RENAME COLUMN seedling_id TO plot_id;
