-- Legacy rows retain NULL ownership and are quarantined from all application queries.
-- Do not infer ownership from an arbitrary organization or identity.
ALTER TABLE incidents ADD COLUMN organization_id UUID;

ALTER TABLE incidents ADD CONSTRAINT incidents_organization_fk
    FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT;

-- NOT VALID preserves existing tenantless rows while enforcing ownership on every
-- new INSERT/UPDATE. Validate and make the column NOT NULL only after a separately
-- reviewed legacy assignment/archive plan. There is no public assignment API.
ALTER TABLE incidents ADD CONSTRAINT incidents_organization_required
    CHECK (organization_id IS NOT NULL) NOT VALID;

CREATE INDEX incidents_organization_created_idx
    ON incidents (organization_id, created_at DESC, id DESC);

CREATE INDEX incidents_organization_status_created_idx
    ON incidents (organization_id, status, created_at DESC, id DESC);
