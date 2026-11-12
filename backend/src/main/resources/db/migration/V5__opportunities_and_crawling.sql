-- =====================================================================
-- V5: Global opportunities and crawl execution records.
-- Opportunities are stored ONCE globally. Tenant-specific relationships
-- (pipeline, notes, relevance) live in tenant_* tables (later phase).
-- =====================================================================

CREATE TABLE crawl_jobs (
    id            UUID PRIMARY KEY,
    source_id     UUID        NOT NULL REFERENCES source_registry (id),
    trigger_type  VARCHAR(20) NOT NULL CHECK (trigger_type IN ('SCHEDULED', 'MANUAL')),
    requested_by  UUID,
    requested_at  TIMESTAMPTZ NOT NULL,
    status        VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'SKIPPED')),
    completed_at  TIMESTAMPTZ,
    skip_reason   VARCHAR(500)
);
CREATE INDEX ix_crawl_jobs_source_time ON crawl_jobs (source_id, requested_at DESC);

CREATE TABLE crawl_job_runs (
    id                  UUID PRIMARY KEY,
    job_id              UUID        NOT NULL REFERENCES crawl_jobs (id),
    source_id           UUID        NOT NULL REFERENCES source_registry (id),
    attempt             INT         NOT NULL DEFAULT 1,
    started_at          TIMESTAMPTZ NOT NULL,
    finished_at         TIMESTAMPTZ,
    status              VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    -- Distinguishes "crawled fine, nothing there" from every kind of failure.
    outcome             VARCHAR(40) CHECK (outcome IN (
        'SUCCESS', 'SUCCESS_ZERO_RESULTS', 'NO_CHANGE', 'CRAWL_FAILED', 'SOURCE_UNAVAILABLE',
        'AUTHENTICATION_REQUIRED', 'ACCESS_DENIED', 'RATE_LIMITED', 'PARSER_FAILED', 'BLOCKED_BY_ROBOTS',
        'NOT_PERMITTED')),
    records_found       INT         NOT NULL DEFAULT 0,
    records_discovered  INT         NOT NULL DEFAULT 0,
    records_updated     INT         NOT NULL DEFAULT 0,
    records_unchanged   INT         NOT NULL DEFAULT 0,
    records_rejected    INT         NOT NULL DEFAULT 0,
    http_status         INT,
    content_hash        VARCHAR(64),
    message             TEXT,
    execution_log       TEXT
);
CREATE INDEX ix_crawl_job_runs_source_time ON crawl_job_runs (source_id, started_at DESC);
CREATE INDEX ix_crawl_job_runs_started ON crawl_job_runs (started_at DESC);

CREATE TABLE crawl_errors (
    id             UUID PRIMARY KEY,
    run_id         UUID        NOT NULL REFERENCES crawl_job_runs (id),
    source_id      UUID        NOT NULL REFERENCES source_registry (id),
    error_type     VARCHAR(40) NOT NULL,
    message        TEXT        NOT NULL,
    url            VARCHAR(2000),
    item_reference VARCHAR(500),
    occurred_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_crawl_errors_source_time ON crawl_errors (source_id, occurred_at DESC);
CREATE INDEX ix_crawl_errors_time ON crawl_errors (occurred_at DESC);

-- Raw evidence of what was fetched from a source.
CREATE TABLE source_observations (
    id            UUID PRIMARY KEY,
    source_id     UUID         NOT NULL REFERENCES source_registry (id),
    run_id        UUID         REFERENCES crawl_job_runs (id),
    url           VARCHAR(2000) NOT NULL,
    http_status   INT,
    content_type  VARCHAR(200),
    content_hash  VARCHAR(64),
    bytes         BIGINT,
    fetched_at    TIMESTAMPTZ  NOT NULL,
    excerpt       TEXT
);
CREATE INDEX ix_source_observations_run ON source_observations (run_id);
CREATE INDEX ix_source_observations_source_time ON source_observations (source_id, fetched_at DESC);

CREATE TABLE opportunities (
    id                          UUID PRIMARY KEY,
    source_id                   UUID         NOT NULL REFERENCES source_registry (id),
    organisation_id             UUID         REFERENCES procurement_organisations (id),
    -- Stable identifier of the record within its source (e.g. OCDS ocid, listing reference or URL).
    source_reference            VARCHAR(500) NOT NULL,
    reference_number            VARCHAR(300),
    title                       VARCHAR(1000) NOT NULL,
    description                 TEXT,
    event_type                  VARCHAR(40)  NOT NULL,
    procurement_category        VARCHAR(40)  NOT NULL DEFAULT 'NOT_SPECIFIED',
    source_category_label       VARCHAR(300),
    status                      VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'AWARDED', 'CANCELLED', 'EXPIRED', 'UNKNOWN')),
    status_inferred             BOOLEAN      NOT NULL DEFAULT FALSE,
    province                    VARCHAR(40),
    locality                    VARCHAR(300),
    mine_site                   VARCHAR(300),
    published_at                TIMESTAMPTZ,
    closing_at                  TIMESTAMPTZ,
    closing_date                DATE,
    closing_time_specified      BOOLEAN      NOT NULL DEFAULT FALSE,
    closing_raw                 VARCHAR(200),
    estimated_value             NUMERIC(20, 2),
    currency                    VARCHAR(3),
    contract_duration           VARCHAR(300),
    briefing_details            TEXT,
    briefing_compulsory         BOOLEAN,
    contact_name                VARCHAR(300),
    contact_email               VARCHAR(320),
    contact_phone               VARCHAR(100),
    source_url                  VARCHAR(2000) NOT NULL,
    evidence_url                VARCHAR(2000),
    content_hash                VARCHAR(64)  NOT NULL,
    duplicate_of_id             UUID         REFERENCES opportunities (id),
    first_discovered_at         TIMESTAMPTZ  NOT NULL,
    last_seen_at                TIMESTAMPTZ  NOT NULL,
    last_checked_at             TIMESTAMPTZ  NOT NULL,
    last_changed_at             TIMESTAMPTZ,
    last_run_id                 UUID         REFERENCES crawl_job_runs (id),
    created_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    version                     BIGINT       NOT NULL DEFAULT 0,
    search_vector               TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', COALESCE(reference_number, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(title, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(source_category_label, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(locality, '')), 'C') ||
        setweight(to_tsvector('english', COALESCE(description, '')), 'C')) STORED,
    CONSTRAINT ux_opportunities_source_reference UNIQUE (source_id, source_reference)
);
CREATE INDEX ix_opportunities_closing_date ON opportunities (closing_date);
CREATE INDEX ix_opportunities_status ON opportunities (status);
CREATE INDEX ix_opportunities_org ON opportunities (organisation_id);
CREATE INDEX ix_opportunities_first_discovered ON opportunities (first_discovered_at DESC);
CREATE INDEX ix_opportunities_province ON opportunities (province);
CREATE INDEX ix_opportunities_reference_lower ON opportunities (LOWER(reference_number));
CREATE INDEX ix_opportunities_search ON opportunities USING GIN (search_vector);

CREATE TABLE opportunity_documents (
    id               UUID PRIMARY KEY,
    opportunity_id   UUID          NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    title            VARCHAR(1000),
    url              VARCHAR(2000) NOT NULL,
    document_type    VARCHAR(40)   NOT NULL DEFAULT 'OTHER',
    file_format      VARCHAR(20),
    published_at     TIMESTAMPTZ,
    first_seen_at    TIMESTAMPTZ   NOT NULL,
    last_seen_at     TIMESTAMPTZ   NOT NULL,
    removed_at       TIMESTAMPTZ,
    CONSTRAINT ux_opportunity_documents UNIQUE (opportunity_id, url)
);

CREATE TABLE opportunity_categories (
    id              UUID PRIMARY KEY,
    opportunity_id  UUID         NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    category_type   VARCHAR(40)  NOT NULL CHECK (category_type IN ('PROCUREMENT_CATEGORY', 'INDUSTRY', 'COMMODITY', 'SERVICE', 'PRODUCT')),
    code            VARCHAR(200) NOT NULL,
    provenance      VARCHAR(20)  NOT NULL CHECK (provenance IN ('SOURCE', 'RULE', 'AI', 'HUMAN')),
    confidence      NUMERIC(4, 3),
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_opportunity_categories UNIQUE (opportunity_id, category_type, code, provenance)
);

CREATE TABLE source_change_events (
    id              UUID PRIMARY KEY,
    opportunity_id  UUID         NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    source_id       UUID         NOT NULL REFERENCES source_registry (id),
    run_id          UUID         REFERENCES crawl_job_runs (id),
    field_name      VARCHAR(100) NOT NULL,
    old_value       TEXT,
    new_value       TEXT,
    material        BOOLEAN      NOT NULL,
    detected_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_source_change_events_opportunity ON source_change_events (opportunity_id, detected_at DESC);
CREATE INDEX ix_source_change_events_time ON source_change_events (detected_at DESC);
