
CREATE TABLE app_users (
    id UUID PRIMARY KEY,

    issuer VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT app_users_identity_unique
        UNIQUE (issuer, subject)
);

CREATE TABLE organizations (
    id UUID PRIMARY KEY,

    name VARCHAR(120) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE organization_memberships (
    id UUID PRIMARY KEY,

    organization_id UUID NOT NULL,
    user_id UUID NOT NULL,

    role VARCHAR(16) NOT NULL,

    CONSTRAINT memberships_organization_fk
        FOREIGN KEY (organization_id)
        REFERENCES organizations(id)
        ON DELETE CASCADE,

    CONSTRAINT memberships_user_fk
        FOREIGN KEY (user_id)
        REFERENCES app_users(id)
        ON DELETE CASCADE,

    CONSTRAINT memberships_unique
        UNIQUE (organization_id, user_id),

    CONSTRAINT memberships_role_check
        CHECK (role IN ('ADMIN', 'ENGINEER', 'VIEWER'))
);

CREATE INDEX memberships_user_id_idx
    ON organization_memberships(user_id);
