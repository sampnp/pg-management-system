-- The property dashboard finds a property's maintenance issues through their beds
CREATE INDEX idx_maintenance_bed ON maintenance_issues (bed_id);
