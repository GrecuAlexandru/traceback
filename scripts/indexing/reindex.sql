-- Trusted local database administration only. No source content or storage keys are printed.
-- Supply org/doc/rev/config with psql -v; config must already exist from an enabled API/worker.
BEGIN;
SELECT id FROM documents WHERE organization_id=:'org'::uuid AND id=:'doc'::uuid FOR UPDATE;
WITH candidate AS (
  SELECT d.organization_id,d.id AS document_id,r.id AS revision_id,j.id AS job_id,j.generation
  FROM documents d JOIN document_revisions r ON r.organization_id=d.organization_id AND r.document_id=d.id AND r.id=d.current_revision_id
  JOIN ingestion_jobs j ON j.organization_id=d.organization_id AND j.document_id=d.id AND j.revision_id=r.id
  WHERE d.organization_id=:'org'::uuid AND d.id=:'doc'::uuid AND r.id=:'rev'::uuid
  AND d.status='AVAILABLE' AND r.status='AVAILABLE' AND j.status='VERIFIED'
  AND EXISTS(SELECT 1 FROM index_configurations WHERE id=:'config')
  AND NOT EXISTS(SELECT 1 FROM index_generations g WHERE g.revision_id=r.id
      AND g.status IN ('PENDING_EXTRACTION','EXTRACTING','EXTRACTED','INDEXING','RETRY'))
), created AS (
  INSERT INTO index_generations(id,organization_id,document_id,revision_id,configuration_id)
  SELECT gen_random_uuid(),organization_id,document_id,revision_id,:'config' FROM candidate
  RETURNING *
)
INSERT INTO ingestion_outbox(id,organization_id,document_id,revision_id,job_id,generation,event_type,index_generation_id)
SELECT gen_random_uuid(),g.organization_id,g.document_id,g.revision_id,c.job_id,c.generation,'INDEX',g.id
FROM created g JOIN candidate c ON c.organization_id=g.organization_id AND c.document_id=g.document_id AND c.revision_id=g.revision_id;
COMMIT;
