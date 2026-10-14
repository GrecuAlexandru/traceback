-- Development administration only. psql -v ON_ERROR_STOP=1 -v org=... -v doc=... -v rev=... -f scripts/ingestion/retry.sql
-- Database credentials are supplied through normal secure psql configuration, never command arguments.
BEGIN;
SELECT id FROM documents WHERE organization_id=:'org'::uuid AND id=:'doc'::uuid FOR UPDATE;
WITH retried AS (
    UPDATE ingestion_jobs j SET status='PENDING', attempts=0, generation=generation+1,
        lease_token=NULL, lease_until=NULL, error_code=NULL, verified_at=NULL, processing_ms=NULL,
        next_attempt_at=clock_timestamp(), updated_at=clock_timestamp()
    WHERE j.organization_id=:'org'::uuid AND j.document_id=:'doc'::uuid AND j.revision_id=:'rev'::uuid
        AND j.status='FAILED' AND EXISTS (
            SELECT 1 FROM documents d JOIN document_revisions r
              ON r.organization_id=d.organization_id AND r.document_id=d.id
            WHERE d.organization_id=j.organization_id AND d.id=j.document_id
              AND d.status='AVAILABLE' AND d.current_revision_id=j.revision_id
              AND r.id=j.revision_id AND r.status='AVAILABLE')
    RETURNING j.*
)
INSERT INTO ingestion_outbox(id,organization_id,document_id,revision_id,job_id,generation)
SELECT gen_random_uuid(),organization_id,document_id,revision_id,id,generation FROM retried;
COMMIT;
