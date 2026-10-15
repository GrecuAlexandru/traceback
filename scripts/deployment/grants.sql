-- Run as schema owner AFTER Flyway, with pre-created LOGIN roles and NO runtime DDL.
-- psql -v ON_ERROR_STOP=1 -v app_role=traceback_api -v worker_role=traceback_worker -f ...
BEGIN;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"app_role", :"worker_role";
GRANT SELECT ON flyway_schema_history TO :"app_role", :"worker_role";
GRANT SELECT ON app_users, organizations, organization_memberships, incidents,
  documents, document_revisions, ingestion_jobs, ingestion_outbox, index_configurations,
  index_generations, extraction_artifacts, document_chunks, chunk_embeddings,
  document_index_heads, conversations, conversation_turns, conversation_messages,
  conversation_evidence TO :"app_role";
GRANT SELECT ON documents, document_revisions, ingestion_jobs, ingestion_outbox,
  index_configurations, index_generations, extraction_artifacts, document_chunks,
  chunk_embeddings, document_index_heads TO :"worker_role";
-- PostgreSQL SELECT FOR UPDATE requires UPDATE on at least one column. Workers
-- lock documents before job/generation rows; grant only this harmless timestamp,
-- never ownership, revision pointers, status or immutable revision metadata.
GRANT UPDATE (modified_at) ON documents TO :"worker_role";
GRANT INSERT, UPDATE ON app_users, organizations, organization_memberships, documents,
  document_revisions, ingestion_jobs, ingestion_outbox, index_configurations,
  index_generations, conversations, conversation_turns, conversation_messages,
  conversation_evidence TO :"app_role";
GRANT INSERT, UPDATE, DELETE ON incidents TO :"app_role";
GRANT INSERT, UPDATE ON ingestion_jobs, ingestion_outbox, index_configurations,
  index_generations, extraction_artifacts, document_chunks, chunk_embeddings,
  document_index_heads TO :"worker_role";
COMMIT;
-- Re-run grants after each migration adding tables; no password/role ownership changes.
