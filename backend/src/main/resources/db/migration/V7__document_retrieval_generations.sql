CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE index_configurations (
    id VARCHAR(64) PRIMARY KEY CHECK (id ~ '^[0-9a-f]{64}$'),
    model VARCHAR(128) NOT NULL,
    model_digest VARCHAR(71) NOT NULL CHECK (model_digest ~ '^sha256:[0-9a-f]{64}$'),
    dimensions INTEGER NOT NULL CHECK (dimensions BETWEEN 1 AND 2000),
    extractor_version VARCHAR(32) NOT NULL,
    chunker_version VARCHAR(32) NOT NULL,
    chunk_size INTEGER NOT NULL CHECK (chunk_size BETWEEN 200 AND 1600),
    overlap INTEGER NOT NULL CHECK (overlap >= 0 AND overlap <= chunk_size / 4),
    max_chunks INTEGER NOT NULL CHECK (max_chunks BETWEEN 1 AND 512),
    max_text INTEGER NOT NULL CHECK (max_text BETWEEN 1 AND 1000000),
    max_pages INTEGER NOT NULL CHECK (max_pages BETWEEN 1 AND 200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, dimensions)
);
CREATE TABLE index_generations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    configuration_id VARCHAR(64) NOT NULL REFERENCES index_configurations(id),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_EXTRACTION' CHECK
      (status IN ('PENDING_EXTRACTION','EXTRACTING','EXTRACTED','INDEXING','INDEXED','RETRY','FAILED','CANCELLED')),
    phase VARCHAR(16) NOT NULL DEFAULT 'EXTRACTION' CHECK (phase IN ('EXTRACTION','INDEXING')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    error_code VARCHAR(32),
    processing_ms BIGINT CHECK (processing_ms >= 0),
    chunk_count INTEGER CHECK (chunk_count BETWEEN 1 AND 512),
    FOREIGN KEY (organization_id,document_id,revision_id) REFERENCES document_revisions(organization_id,document_id,id),
    UNIQUE (organization_id,document_id,revision_id,id),
    UNIQUE (organization_id,document_id,revision_id,id,configuration_id),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((status IN ('EXTRACTING','EXTRACTED','INDEXING')) = (lease_token IS NOT NULL)),
    CHECK (status <> 'INDEXED' OR (published_at IS NOT NULL AND chunk_count IS NOT NULL))
);
CREATE UNIQUE INDEX index_one_unfinished_revision ON index_generations(revision_id)
    WHERE status IN ('PENDING_EXTRACTION','EXTRACTING','EXTRACTED','INDEXING','RETRY');
CREATE INDEX index_generation_due ON index_generations(next_attempt_at,lease_until)
    WHERE status IN ('PENDING_EXTRACTION','EXTRACTING','EXTRACTED','INDEXING','RETRY');
CREATE INDEX index_generation_tenant ON index_generations(organization_id,document_id,revision_id,updated_at DESC);

CREATE TABLE extraction_artifacts (
    generation_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    blocks JSONB NOT NULL CHECK (jsonb_typeof(blocks)='array' AND jsonb_array_length(blocks)>0 AND octet_length(blocks::text)<=9000000),
    checksum VARCHAR(64) NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    FOREIGN KEY (organization_id,document_id,revision_id,generation_id)
        REFERENCES index_generations(organization_id,document_id,revision_id,id)
);
CREATE TABLE document_chunks (
    generation_id UUID NOT NULL,
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    configuration_id VARCHAR(64) NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 511),
    content TEXT NOT NULL CHECK (length(content) BETWEEN 1 AND 1600),
    section TEXT CHECK (length(section)<=1600),
    page_start INTEGER CHECK (page_start BETWEEN 1 AND 200),
    page_end INTEGER CHECK (page_end >= page_start AND page_end<=200),
    source_start INTEGER NOT NULL CHECK (source_start>=0),
    source_end INTEGER NOT NULL CHECK (source_end>=source_start),
    checksum VARCHAR(64) NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    terms TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple',content)) STORED,
    PRIMARY KEY (generation_id,id),
    UNIQUE (generation_id,ordinal),
    UNIQUE (organization_id,document_id,revision_id,generation_id,id,configuration_id),
    FOREIGN KEY (organization_id,document_id,revision_id,generation_id,configuration_id)
        REFERENCES index_generations(organization_id,document_id,revision_id,id,configuration_id),
    CHECK ((page_start IS NULL) = (page_end IS NULL))
);
CREATE INDEX document_chunks_terms ON document_chunks USING GIN(terms);
CREATE INDEX document_chunks_tenant ON document_chunks(organization_id,revision_id,generation_id);
CREATE TABLE chunk_embeddings (
    organization_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    chunk_id UUID NOT NULL,
    configuration_id VARCHAR(64) NOT NULL,
    dimensions INTEGER NOT NULL,
    embedding VECTOR NOT NULL CHECK (vector_dims(embedding)=dimensions AND vector_norm(embedding)>0),
    PRIMARY KEY (generation_id,chunk_id),
    FOREIGN KEY (configuration_id,dimensions) REFERENCES index_configurations(id,dimensions),
    FOREIGN KEY (organization_id,document_id,revision_id,generation_id,chunk_id,configuration_id)
        REFERENCES document_chunks(organization_id,document_id,revision_id,generation_id,id,configuration_id)
);
CREATE TABLE document_index_heads (
    organization_id UUID NOT NULL,
    document_id UUID NOT NULL,
    revision_id UUID PRIMARY KEY,
    generation_id UUID NOT NULL UNIQUE,
    configuration_id VARCHAR(64) NOT NULL,
    FOREIGN KEY (organization_id,document_id,revision_id,generation_id,configuration_id)
        REFERENCES index_generations(organization_id,document_id,revision_id,id,configuration_id)
);
CREATE FUNCTION validate_index_publication() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected INTEGER; actual INTEGER; chunks INTEGER;
BEGIN
    SELECT chunk_count INTO expected FROM index_generations WHERE id=NEW.generation_id AND status='INDEXED';
    SELECT count(*) INTO actual FROM document_chunks c JOIN chunk_embeddings e
      ON e.generation_id=c.generation_id AND e.chunk_id=c.id WHERE c.generation_id=NEW.generation_id;
    SELECT count(*) INTO chunks FROM document_chunks WHERE generation_id=NEW.generation_id;
    IF expected IS NULL OR expected<>actual OR chunks<>actual OR actual=0 THEN RAISE EXCEPTION 'Incomplete index publication'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER index_publication_complete BEFORE INSERT OR UPDATE ON document_index_heads
    FOR EACH ROW EXECUTE FUNCTION validate_index_publication();

ALTER TABLE ingestion_outbox ADD COLUMN event_type VARCHAR(8) NOT NULL DEFAULT 'VERIFY' CHECK (event_type IN ('VERIFY','INDEX'));
ALTER TABLE ingestion_outbox ADD COLUMN index_generation_id UUID;
ALTER TABLE ingestion_outbox ADD CONSTRAINT indexing_event_owner
    FOREIGN KEY (organization_id,document_id,revision_id,index_generation_id)
        REFERENCES index_generations(organization_id,document_id,revision_id,id);
ALTER TABLE ingestion_outbox ADD CONSTRAINT indexing_event_type CHECK ((event_type='INDEX')=(index_generation_id IS NOT NULL));
ALTER TABLE ingestion_outbox DROP CONSTRAINT ingestion_outbox_job_id_generation_key;
CREATE UNIQUE INDEX verification_event_unique ON ingestion_outbox(job_id,generation) WHERE event_type='VERIFY';
CREATE UNIQUE INDEX indexing_event_unique ON ingestion_outbox(index_generation_id) WHERE event_type='INDEX';

CREATE FUNCTION protect_index_configuration() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Index configurations are immutable; create a new configuration'; END $$;
CREATE TRIGGER index_configuration_immutable BEFORE UPDATE ON index_configurations
    FOR EACH ROW EXECUTE FUNCTION protect_index_configuration();
