-- Phase 6: maintenance issues, reported by tenants and handled by staff.

-- 1) Tenant logins. A tenant reports their own issues, so they need an account.
--    A TENANT user is linked to exactly one tenant; staff accounts (ADMIN, MANAGER) are not linked to any.
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('ADMIN', 'MANAGER', 'TENANT'));

-- A tenant can only be deleted when they have no history, and then their login goes with them
ALTER TABLE users ADD COLUMN tenant_id UUID REFERENCES tenants (id) ON DELETE CASCADE;
ALTER TABLE users ADD CONSTRAINT uq_users_tenant UNIQUE (tenant_id);
ALTER TABLE users ADD CONSTRAINT users_tenant_link_check CHECK ((role = 'TENANT') = (tenant_id IS NOT NULL));

-- 2) Maintenance issues. V1 already created maintenance_requests (not used by any code yet), so adapt it
--    instead of creating a second table. It is renamed to match the MaintenanceIssue model, because in
--    this project "...Request" classes are API request bodies.
ALTER TABLE maintenance_requests RENAME TO maintenance_issues;

-- Every issue belongs to a tenant
ALTER TABLE maintenance_issues ALTER COLUMN tenant_id SET NOT NULL;

-- Where the problem is: the bed the tenant was in when they reported it. The room and property are
-- found through the bed (a bed never moves to another room), so they are not stored a second time.
-- The bed stays on the issue after the tenant checks out, so old issues still say where the problem was.
ALTER TABLE maintenance_issues DROP COLUMN property_id;   -- also drops idx_maintenance_property
ALTER TABLE maintenance_issues ADD COLUMN bed_id UUID NOT NULL REFERENCES beds (id);

-- Title and description must contain text; description is now required and limited in length
ALTER TABLE maintenance_issues ADD CONSTRAINT maintenance_issues_title_check CHECK (btrim(title) <> '');
ALTER TABLE maintenance_issues ALTER COLUMN description SET NOT NULL;
ALTER TABLE maintenance_issues ADD CONSTRAINT maintenance_issues_description_check
    CHECK (btrim(description) <> '' AND char_length(description) <= 2000);

ALTER TABLE maintenance_issues ADD COLUMN category VARCHAR(20) NOT NULL
    CHECK (category IN ('PLUMBING', 'ELECTRICAL', 'CLEANING', 'FURNITURE', 'APPLIANCE', 'INTERNET', 'OTHER'));

ALTER TABLE maintenance_issues DROP CONSTRAINT maintenance_requests_priority_check;
ALTER TABLE maintenance_issues ADD CONSTRAINT maintenance_issues_priority_check
    CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT'));

-- Lifecycle: OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED (the allowed moves are checked in MaintenanceStatus)
ALTER TABLE maintenance_issues DROP CONSTRAINT maintenance_requests_status_check;
ALTER TABLE maintenance_issues ADD CONSTRAINT maintenance_issues_status_check
    CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'));

-- The staff member (ADMIN or MANAGER) handling the issue; null until someone is assigned
ALTER TABLE maintenance_issues ADD COLUMN assigned_to UUID REFERENCES users (id);

-- Set when the issue is resolved and kept when it is closed; cleared if a resolved issue is reopened
ALTER TABLE maintenance_issues ADD COLUMN resolved_at TIMESTAMPTZ;
ALTER TABLE maintenance_issues ADD CONSTRAINT maintenance_issues_resolved_at_check
    CHECK ((status IN ('RESOLVED', 'CLOSED')) = (resolved_at IS NOT NULL));

-- The tenant_id foreign key keeps the default NO ACTION: a tenant with maintenance history cannot be deleted
CREATE INDEX idx_maintenance_tenant ON maintenance_issues (tenant_id);
CREATE INDEX idx_maintenance_created ON maintenance_issues (created_at);
