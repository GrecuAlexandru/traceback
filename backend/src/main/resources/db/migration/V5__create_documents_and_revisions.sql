CREATE TABLE documents (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    title VARCHAR(200) NOT NULL CHECK (length(trim(title)) > 0),
    created_by UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('UPLOADING', 'AVAILABLE', 'FAILED', 'DELETED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    current_revision_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (organization_id, id),
    CHECK (status <> 'AVAILABLE' OR current_revision_id IS NOT NULL)
);

CREATE TABLE document_revisions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    original_filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(32) NOT NULL CHECK (media_type IN ('application/pdf', 'text/markdown', 'text/plain')),
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    checksum VARCHAR(64) NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    storage_key UUID NOT NULL UNIQUE,
    uploaded_by UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('UPLOADING', 'AVAILABLE', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (organization_id, document_id) REFERENCES documents(organization_id, id) ON DELETE RESTRICT,
    UNIQUE (document_id, revision_number),
    UNIQUE (organization_id, document_id, id)
);
ALTER TABLE documents ADD CONSTRAINT documents_current_revision_fk
    FOREIGN KEY (organization_id, id, current_revision_id)
    REFERENCES document_revisions(organization_id, document_id, id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX document_one_pending_revision_idx ON document_revisions(document_id) WHERE status = 'UPLOADING';
CREATE INDEX documents_tenant_date_idx ON documents(organization_id, created_at DESC, id DESC);
CREATE INDEX documents_tenant_status_date_idx ON documents(organization_id, status, created_at DESC, id DESC);
CREATE INDEX document_revisions_tenant_history_idx ON document_revisions(organization_id, document_id, revision_number DESC);

-- Content metadata is write-once. Only a pending attempt can become available or failed.
CREATE FUNCTION protect_document_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW) - 'status') IS DISTINCT FROM (to_jsonb(OLD) - 'status')
       OR OLD.status <> 'UPLOADING' OR NEW.status NOT IN ('AVAILABLE', 'FAILED') THEN
        RAISE EXCEPTION 'Document revisions are immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER document_revision_immutable BEFORE UPDATE ON document_revisions
    FOR EACH ROW EXECUTE FUNCTION protect_document_revision();
