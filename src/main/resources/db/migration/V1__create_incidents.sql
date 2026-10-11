CREATE TABLE incidents (
    id UUID PRIMARY KEY,

    title VARCHAR(200) NOT NULL,

    description TEXT NOT NULL,

    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT incidents_status_check
        CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED'))
);
