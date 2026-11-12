-- =====================================================================
-- V9: AI provider settings and audit, assistant conversations, AI
-- classification results, and tenant opportunity matching.
-- =====================================================================

-- Runtime AI settings (non-secret). The API key is only ever read from the environment.
INSERT INTO system_settings (setting_key, setting_value, description, updated_at) VALUES
 ('ai.enabled', 'true', 'Master switch for AI features (they also need a configured provider and API key)', NOW()),
 ('ai.model', 'claude-opus-5-5', 'Claude model id used for AI features', NOW()),
 ('ai.effort', 'medium', 'Reasoning effort: low, medium, high, xhigh, max', NOW()),
 ('ai.assistant.enabled', 'true', 'Enable the in-app assistant', NOW()),
 ('ai.classification.enabled', 'false', 'Enable AI classification of opportunities (background and on demand)', NOW()),
 ('ai.classification.daily_limit', '200', 'Maximum AI classifications per day (cost control)', NOW()),
 ('ai.assistant.daily_limit_per_tenant', '200', 'Maximum assistant requests per tenant per day (cost control)', NOW()),
 ('ai.match_explanation.enabled', 'true', 'Allow users to request an AI explanation of a match', NOW())
ON CONFLICT (setting_key) DO NOTHING;

CREATE TABLE ai_processing_audit (
    id              UUID PRIMARY KEY,
    tenant_id       UUID REFERENCES tenants (id),
    user_id         UUID REFERENCES users (id),
    feature         VARCHAR(30)  NOT NULL CHECK (feature IN ('ASSISTANT', 'CLASSIFICATION', 'MATCH_EXPLANATION')),
    provider        VARCHAR(30)  NOT NULL,
    model           VARCHAR(100) NOT NULL,
    prompt_version  VARCHAR(30)  NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED', 'REFUSED', 'LIMITED')),
    input_tokens    BIGINT,
    output_tokens   BIGINT,
    tool_calls      INT          NOT NULL DEFAULT 0,
    latency_ms      BIGINT,
    error           VARCHAR(2000),
    subject_id      UUID,
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_ai_audit_tenant_time ON ai_processing_audit (tenant_id, created_at DESC);
CREATE INDEX ix_ai_audit_feature_time ON ai_processing_audit (feature, created_at DESC);

-- Background AI work queue (classification).
CREATE TABLE ai_processing_jobs (
    id              UUID PRIMARY KEY,
    job_type        VARCHAR(30)  NOT NULL CHECK (job_type IN ('CLASSIFY_OPPORTUNITY')),
    subject_id      UUID         NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    attempts        INT          NOT NULL DEFAULT 0,
    error           VARCHAR(2000),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_ai_jobs UNIQUE (job_type, subject_id)
);

CREATE TABLE ai_classification_results (
    id              UUID PRIMARY KEY,
    opportunity_id  UUID          NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    model           VARCHAR(100)  NOT NULL,
    prompt_version  VARCHAR(30)   NOT NULL,
    result          JSONB         NOT NULL,
    confidence      NUMERIC(4, 3),
    overridden_by   UUID REFERENCES users (id),
    override_note   VARCHAR(1000),
    created_at      TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_ai_classification_opportunity ON ai_classification_results (opportunity_id, created_at DESC);

CREATE TABLE assistant_conversations (
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL REFERENCES tenants (id),
    user_id     UUID         NOT NULL REFERENCES users (id),
    title       VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_assistant_conversations_user ON assistant_conversations (tenant_id, user_id, updated_at DESC);

CREATE TABLE assistant_messages (
    id               UUID PRIMARY KEY,
    conversation_id  UUID         NOT NULL REFERENCES assistant_conversations (id) ON DELETE CASCADE,
    tenant_id        UUID         NOT NULL REFERENCES tenants (id),
    role             VARCHAR(20)  NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content          TEXT         NOT NULL,
    tool_trace       JSONB,
    created_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_assistant_messages_conversation ON assistant_messages (conversation_id, created_at);

-- Per-tenant matching configuration.
CREATE TABLE tenant_match_settings (
    tenant_id                   UUID PRIMARY KEY REFERENCES tenants (id),
    weight_services             INT NOT NULL DEFAULT 30,
    weight_industry             INT NOT NULL DEFAULT 20,
    weight_capability           INT NOT NULL DEFAULT 20,
    weight_geography            INT NOT NULL DEFAULT 10,
    weight_registrations        INT NOT NULL DEFAULT 10,
    weight_other                INT NOT NULL DEFAULT 10,
    strong_threshold            INT NOT NULL DEFAULT 70,
    possible_threshold          INT NOT NULL DEFAULT 45,
    updated_at                  TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_weights CHECK (weight_services + weight_industry + weight_capability + weight_geography
                                 + weight_registrations + weight_other = 100)
);

-- Explainable match of a global opportunity to a tenant's profile (recomputed when either changes).
CREATE TABLE opportunity_matches (
    id               UUID PRIMARY KEY,
    tenant_id        UUID         NOT NULL REFERENCES tenants (id),
    opportunity_id   UUID         NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    score            INT          NOT NULL CHECK (score BETWEEN 0 AND 100),
    label            VARCHAR(30)  NOT NULL CHECK (label IN ('STRONG_MATCH', 'POSSIBLE_MATCH', 'WEAK_MATCH', 'REQUIRES_MANUAL_REVIEW')),
    dimension_scores JSONB        NOT NULL,
    matched          JSONB        NOT NULL,
    missing          JSONB        NOT NULL,
    disqualifiers    JSONB        NOT NULL,
    ai_explanation   JSONB,
    profile_version  BIGINT       NOT NULL,
    opportunity_hash VARCHAR(64)  NOT NULL,
    computed_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_opportunity_matches UNIQUE (tenant_id, opportunity_id)
);
CREATE INDEX ix_opportunity_matches_tenant_score ON opportunity_matches (tenant_id, score DESC);
