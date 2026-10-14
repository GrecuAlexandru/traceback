-- Existing UUID keys retain their LOCAL meaning. V1-V5 are immutable.
ALTER TABLE document_revisions ADD COLUMN storage_provider VARCHAR(8) NOT NULL DEFAULT 'LOCAL'
    CHECK (storage_provider IN ('LOCAL', 'S3'));
-- V5's to_jsonb immutable-content trigger automatically protects this new column.
CREATE TABLE ingestion_jobs (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL UNIQUE,
    generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'RETRY', 'VERIFIED', 'FAILED', 'CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    error_code VARCHAR(32),
    verified_at TIMESTAMPTZ,
    processing_ms BIGINT CHECK (processing_ms >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (organization_id, document_id, revision_id)
        REFERENCES document_revisions(organization_id, document_id, id) ON DELETE RESTRICT,
    UNIQUE (organization_id, document_id, revision_id, id),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (status <> 'VERIFIED' OR (verified_at IS NOT NULL AND processing_ms IS NOT NULL))
);
CREATE TABLE ingestion_outbox (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    job_id UUID NOT NULL,
    generation INTEGER NOT NULL CHECK (generation >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    error_code VARCHAR(32),
    FOREIGN KEY (organization_id, document_id, revision_id, job_id)
        REFERENCES ingestion_jobs(organization_id, document_id, revision_id, id) ON DELETE RESTRICT,
    UNIQUE (job_id, generation),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);
CREATE INDEX ingestion_outbox_due_idx ON ingestion_outbox(next_attempt_at) WHERE delivered_at IS NULL;
CREATE INDEX ingestion_jobs_recovery_idx ON ingestion_jobs(next_attempt_at, lease_until)
    WHERE status IN ('PENDING', 'RETRY', 'RUNNING');
CREATE INDEX ingestion_jobs_tenant_idx ON ingestion_jobs(organization_id, document_id, revision_id);
