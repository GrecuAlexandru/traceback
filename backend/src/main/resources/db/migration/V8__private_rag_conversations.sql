-- Append-only: existing incidents, documents and published indexes are untouched.
CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    owner_id UUID NOT NULL REFERENCES app_users(id),
    title VARCHAR(120) NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 120),
    next_sequence BIGINT NOT NULL DEFAULT 0 CHECK (next_sequence >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    UNIQUE (organization_id, id)
);
CREATE INDEX conversations_owner_list ON conversations(organization_id,owner_id,created_at DESC,id DESC) WHERE deleted_at IS NULL;
CREATE TABLE conversation_turns (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    request_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(32) NOT NULL CHECK (status IN ('GENERATING','ANSWERED','INSUFFICIENT_EVIDENCE','FAILED')),
    error_code VARCHAR(32),
    deadline TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(metadata)='object' AND octet_length(metadata::text)<=8192),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    FOREIGN KEY (organization_id,conversation_id) REFERENCES conversations(organization_id,id),
    UNIQUE (conversation_id,request_key),
    UNIQUE (organization_id,conversation_id,id),
    CHECK ((status='GENERATING')=(completed_at IS NULL)),
    CHECK (status <> 'FAILED' OR error_code IS NOT NULL)
);
CREATE UNIQUE INDEX conversation_one_generation ON conversation_turns(conversation_id) WHERE status='GENERATING';
CREATE TABLE conversation_messages (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    turn_id UUID NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence>=0),
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER','ASSISTANT')),
    content TEXT CHECK (octet_length(content) BETWEEN 1 AND 16000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (organization_id,conversation_id,turn_id) REFERENCES conversation_turns(organization_id,conversation_id,id),
    UNIQUE (conversation_id,sequence),
    UNIQUE (turn_id,role),
    UNIQUE (organization_id,conversation_id,id),
    CHECK (role <> 'USER' OR content IS NOT NULL)
);
CREATE TABLE conversation_evidence (
    organization_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    message_id UUID NOT NULL,
    reference VARCHAR(8) NOT NULL CHECK (reference ~ '^[SH][1-9][0-9]{0,2}$'),
    document_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    generation_id UUID NOT NULL,
    chunk_id UUID NOT NULL,
    configuration_id VARCHAR(64) NOT NULL,
    cited BOOLEAN NOT NULL,
    PRIMARY KEY (message_id,reference),
    FOREIGN KEY (organization_id,conversation_id,message_id) REFERENCES conversation_messages(organization_id,conversation_id,id),
    FOREIGN KEY (organization_id,document_id,revision_id,generation_id,chunk_id,configuration_id)
        REFERENCES document_chunks(organization_id,document_id,revision_id,generation_id,id,configuration_id),
    CHECK (NOT cited OR reference LIKE 'S%')
);
CREATE INDEX conversation_evidence_source ON conversation_evidence(organization_id,document_id,revision_id,generation_id);
