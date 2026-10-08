-- ACTIVE must mean "currently checked in to a bed". V1 made every new tenant ACTIVE,
-- even before they had a bed, so add PENDING ("registered, not checked in yet") as the default.
-- Lifecycle: PENDING -> (check-in) -> ACTIVE -> (check-out) -> CHECKED_OUT -> (check-in again) -> ACTIVE

ALTER TABLE tenants DROP CONSTRAINT tenants_status_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_status_check
    CHECK (status IN ('PENDING', 'ACTIVE', 'CHECKED_OUT'));
ALTER TABLE tenants ALTER COLUMN status SET DEFAULT 'PENDING';

-- Fix any existing ACTIVE tenants that have no current bed
UPDATE tenants t
SET status = CASE
                 WHEN EXISTS (SELECT 1 FROM tenant_bed_history h WHERE h.tenant_id = t.id) THEN 'CHECKED_OUT'
                 ELSE 'PENDING'
             END
WHERE t.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM tenant_bed_history h WHERE h.tenant_id = t.id AND h.check_out IS NULL);

-- Rent must be a positive amount (a zero-rent tenant is almost certainly a data entry mistake)
ALTER TABLE tenants DROP CONSTRAINT tenants_monthly_rent_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_monthly_rent_check CHECK (monthly_rent > 0);
