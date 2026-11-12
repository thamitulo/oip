-- =====================================================================
-- V10: Tenant bid pipeline (tenant-specific; never changes the global
-- opportunity) and notifications.
-- =====================================================================

CREATE TABLE tenant_opportunities (
    id               UUID PRIMARY KEY,
    tenant_id        UUID         NOT NULL REFERENCES tenants (id),
    opportunity_id   UUID         NOT NULL REFERENCES opportunities (id),
    stage            VARCHAR(40)  NOT NULL CHECK (stage IN ('DISCOVERED', 'UNDER_REVIEW', 'SHORTLISTED', 'GO_NO_GO',
        'DOCUMENTS_BEING_PREPARED', 'BID_IN_PROGRESS', 'SUBMITTED', 'AWARDED', 'UNSUCCESSFUL', 'WITHDRAWN', 'EXPIRED')),
    priority         VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    assigned_to      UUID REFERENCES users (id),
    submitted_at     TIMESTAMPTZ,
    outcome_notes    TEXT,
    bid_value        NUMERIC(20, 2),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    created_by       UUID,
    updated_by       UUID,
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ux_tenant_opportunities UNIQUE (tenant_id, opportunity_id)
);
CREATE INDEX ix_tenant_opportunities_tenant_stage ON tenant_opportunities (tenant_id, stage);
CREATE INDEX ix_tenant_opportunities_assignee ON tenant_opportunities (assigned_to);

CREATE TABLE bid_pipeline_history (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID        NOT NULL REFERENCES tenants (id),
    tenant_opportunity_id   UUID        NOT NULL REFERENCES tenant_opportunities (id) ON DELETE CASCADE,
    event_type              VARCHAR(30) NOT NULL,
    from_value              VARCHAR(200),
    to_value                VARCHAR(200),
    comment                 VARCHAR(2000),
    actor_user_id           UUID,
    occurred_at             TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_bid_pipeline_history_item ON bid_pipeline_history (tenant_opportunity_id, occurred_at DESC);

CREATE TABLE opportunity_notes (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID          NOT NULL REFERENCES tenants (id),
    tenant_opportunity_id   UUID          NOT NULL REFERENCES tenant_opportunities (id) ON DELETE CASCADE,
    author_id               UUID          NOT NULL REFERENCES users (id),
    body                    VARCHAR(10000) NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_opportunity_notes_item ON opportunity_notes (tenant_opportunity_id, created_at DESC);

CREATE TABLE opportunity_tasks (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID         NOT NULL REFERENCES tenants (id),
    tenant_opportunity_id   UUID         NOT NULL REFERENCES tenant_opportunities (id) ON DELETE CASCADE,
    title                   VARCHAR(300) NOT NULL,
    due_at                  TIMESTAMPTZ,
    assignee_id             UUID REFERENCES users (id),
    status                  VARCHAR(20)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE', 'CANCELLED')),
    remind_at               TIMESTAMPTZ,
    reminder_sent_at        TIMESTAMPTZ,
    created_by              UUID         NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL,
    completed_at            TIMESTAMPTZ
);
CREATE INDEX ix_opportunity_tasks_tenant ON opportunity_tasks (tenant_id, status, due_at);

CREATE TABLE bid_decisions (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID         NOT NULL REFERENCES tenants (id),
    tenant_opportunity_id   UUID         NOT NULL REFERENCES tenant_opportunities (id) ON DELETE CASCADE,
    decision                VARCHAR(10)  NOT NULL CHECK (decision IN ('GO', 'NO_GO')),
    rationale               VARCHAR(5000),
    decided_by              UUID         NOT NULL REFERENCES users (id),
    decided_at              TIMESTAMPTZ  NOT NULL
);

CREATE TABLE bid_team_members (
    tenant_id               UUID        NOT NULL REFERENCES tenants (id),
    tenant_opportunity_id   UUID        NOT NULL REFERENCES tenant_opportunities (id) ON DELETE CASCADE,
    user_id                 UUID        NOT NULL REFERENCES users (id),
    responsibility          VARCHAR(200),
    PRIMARY KEY (tenant_opportunity_id, user_id)
);

CREATE TABLE bid_documents (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID         NOT NULL REFERENCES tenants (id),
    tenant_opportunity_id   UUID         NOT NULL REFERENCES tenant_opportunities (id) ON DELETE CASCADE,
    file_name               VARCHAR(300) NOT NULL,
    content_type            VARCHAR(150) NOT NULL,
    size_bytes              BIGINT       NOT NULL,
    storage_key             VARCHAR(500) NOT NULL,
    checksum_sha256         VARCHAR(64)  NOT NULL,
    uploaded_by             UUID         NOT NULL,
    uploaded_at             TIMESTAMPTZ  NOT NULL
);

CREATE TABLE notification_preferences (
    tenant_id              UUID        NOT NULL REFERENCES tenants (id),
    user_id                UUID        NOT NULL REFERENCES users (id),
    in_app_enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    email_enabled          BOOLEAN     NOT NULL DEFAULT TRUE,
    email_frequency        VARCHAR(20) NOT NULL DEFAULT 'DAILY_DIGEST' CHECK (email_frequency IN ('IMMEDIATE', 'DAILY_DIGEST', 'OFF')),
    min_match_score        INT         NOT NULL DEFAULT 60 CHECK (min_match_score BETWEEN 0 AND 100),
    urgent_alerts          BOOLEAN     NOT NULL DEFAULT TRUE,
    closing_reminder_days  INT         NOT NULL DEFAULT 3 CHECK (closing_reminder_days BETWEEN 0 AND 30),
    provinces              VARCHAR(500),
    event_types            VARCHAR(500),
    sectors                VARCHAR(200),
    updated_at             TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE notifications (
    id               UUID PRIMARY KEY,
    tenant_id        UUID          NOT NULL REFERENCES tenants (id),
    user_id          UUID          NOT NULL REFERENCES users (id),
    type             VARCHAR(40)   NOT NULL CHECK (type IN ('NEW_MATCH', 'CLOSING_SOON', 'CLOSING_DATE_CHANGED',
        'NEW_DOCUMENT', 'STATUS_CHANGED', 'OPPORTUNITY_CHANGED', 'ASSIGNED', 'TASK_DUE', 'SOURCE_FAILURE')),
    title            VARCHAR(300)  NOT NULL,
    body             VARCHAR(2000),
    link             VARCHAR(500),
    opportunity_id   UUID REFERENCES opportunities (id) ON DELETE CASCADE,
    dedup_key        VARCHAR(300)  NOT NULL,
    read_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ux_notifications_dedup UNIQUE (user_id, dedup_key)
);
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);

CREATE TABLE notification_deliveries (
    id               UUID PRIMARY KEY,
    notification_id  UUID         NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    channel          VARCHAR(20)  NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP')),
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    attempts         INT          NOT NULL DEFAULT 0,
    sent_at          TIMESTAMPTZ,
    error            VARCHAR(1000),
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_notification_deliveries UNIQUE (notification_id, channel)
);
CREATE INDEX ix_notification_deliveries_status ON notification_deliveries (status, created_at);

-- POPIA: account deletion anonymises the user; keep when it happened.
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;
