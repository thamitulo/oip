-- Account e-mail flows: password reset, team invitations and e-mail verification.
-- Only a SHA-256 hash of each token is stored; tokens are single use and expire.
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;

CREATE TABLE account_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose     VARCHAR(30)  NOT NULL CHECK (purpose IN ('PASSWORD_RESET', 'INVITATION', 'EMAIL_VERIFICATION')),
    token_hash  VARCHAR(64)  NOT NULL,
    tenant_id   UUID REFERENCES tenants (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  UUID
);
CREATE UNIQUE INDEX ux_account_tokens_hash ON account_tokens (token_hash);
CREATE INDEX ix_account_tokens_user ON account_tokens (user_id, purpose);

-- Users created by invitation have no usable password until they accept.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_status_check;
ALTER TABLE users ADD CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'DISABLED', 'INVITED'));
