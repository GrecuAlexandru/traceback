-- Explicit one-current-revision backfill; never scans or assigns historical/unowned data.
-- Repeated runs are idempotent. Existing FAILED verification uses ingestion/retry.sql instead.
BEGIN;
SELECT id FROM documents WHERE organization_id=:'org'::uuid AND id=:'doc'::uuid FOR UPDATE;
WITH created AS (
  INSERT INTO ingestion_jobs(id,organization_id,document_id,revision_id)
  SELECT gen_random_uuid(),d.organization_id,d.id,r.id FROM documents d
  JOIN document_revisions r ON r.organization_id=d.organization_id AND r.document_id=d.id AND r.id=d.current_revision_id
  WHERE d.organization_id=:'org'::uuid AND d.id=:'doc'::uuid AND r.id=:'rev'::uuid
  AND d.status='AVAILABLE' AND r.status='AVAILABLE'
  ON CONFLICT (revision_id) DO NOTHING RETURNING *
)
INSERT INTO ingestion_outbox(id,organization_id,document_id,revision_id,job_id,generation)
SELECT gen_random_uuid(),organization_id,document_id,revision_id,id,generation FROM created;
COMMIT;
