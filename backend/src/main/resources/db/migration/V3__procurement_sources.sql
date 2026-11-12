-- =====================================================================
-- V3: Procurement organisations, mining companies, supplier platforms,
-- procurement channels, source registry and crawl schedules.
-- =====================================================================

CREATE TABLE reference_codes (
    code_type   VARCHAR(40)  NOT NULL,
    code        VARCHAR(80)  NOT NULL,
    label       VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    sort_order  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (code_type, code)
);

CREATE TABLE procurement_organisations (
    id                         UUID PRIMARY KEY,
    name                       VARCHAR(300) NOT NULL,
    short_name                 VARCHAR(100),
    organisation_type          VARCHAR(40)  NOT NULL CHECK (organisation_type IN (
        'NATIONAL_DEPARTMENT', 'PROVINCIAL_DEPARTMENT', 'MUNICIPALITY', 'MUNICIPAL_ENTITY', 'PUBLIC_ENTITY',
        'STATE_OWNED_COMPANY', 'EDUCATIONAL_INSTITUTION', 'WATER_BOARD', 'ENERGY_ENTITY', 'TRANSPORT_ENTITY',
        'HEALTHCARE_INSTITUTION', 'MINING_COMPANY', 'PRIVATE_COMPANY', 'AGGREGATOR', 'OTHER')),
    sector                     VARCHAR(20)  NOT NULL CHECK (sector IN ('GOVERNMENT', 'MINING', 'PRIVATE', 'AGGREGATOR')),
    website                    VARCHAR(500),
    website_verification       VARCHAR(30)  NOT NULL DEFAULT 'UNVERIFIED'
        CHECK (website_verification IN ('UNVERIFIED', 'VERIFIED', 'UNREACHABLE', 'BLOCKED')),
    website_verified_at        TIMESTAMPTZ,
    province                   VARCHAR(40),
    country                    VARCHAR(2)   NOT NULL DEFAULT 'ZA',
    external_source            VARCHAR(50),
    external_id                VARCHAR(100),
    notes                      TEXT,
    created_at                 TIMESTAMPTZ  NOT NULL,
    updated_at                 TIMESTAMPTZ  NOT NULL,
    created_by                 UUID,
    updated_by                 UUID,
    version                    BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_procurement_organisations_name ON procurement_organisations (LOWER(name));
CREATE UNIQUE INDEX ux_procurement_organisations_external
    ON procurement_organisations (external_source, external_id) WHERE external_source IS NOT NULL;
CREATE INDEX ix_procurement_organisations_sector ON procurement_organisations (sector);

CREATE TABLE mining_companies (
    id                   UUID PRIMARY KEY,
    organisation_id      UUID NOT NULL UNIQUE REFERENCES procurement_organisations (id),
    primary_commodities  VARCHAR(500),
    notes                TEXT,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL
);

CREATE TABLE mining_operations (
    id                 UUID PRIMARY KEY,
    mining_company_id  UUID         NOT NULL REFERENCES mining_companies (id),
    name               VARCHAR(200) NOT NULL,
    province           VARCHAR(40),
    locality           VARCHAR(200),
    commodity          VARCHAR(200),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_mining_operations UNIQUE (mining_company_id, name)
);

CREATE TABLE supplier_platforms (
    id            UUID PRIMARY KEY,
    name          VARCHAR(200) NOT NULL UNIQUE,
    platform_type VARCHAR(40)  NOT NULL CHECK (platform_type IN (
        'GOVERNMENT_REGISTRY', 'GOVERNMENT_PORTAL', 'SAP_ARIBA', 'COUPA', 'B1SA', 'SALESFORCE_PORTAL',
        'FABRIC_WORKFLOWS', 'DYNAMICS_365', 'CUSTOM_PORTAL', 'OTHER')),
    website       VARCHAR(500),
    access_notes  TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE procurement_channels (
    id                   UUID PRIMARY KEY,
    organisation_id      UUID         NOT NULL REFERENCES procurement_organisations (id),
    channel_type         VARCHAR(40)  NOT NULL CHECK (channel_type IN (
        'PUBLIC_TENDER_LISTING', 'EOI_ESD_PAGE', 'SUPPLIER_REGISTRATION', 'SUPPLIER_PLATFORM',
        'LOGIN_RFQ_SYSTEM', 'PROCUREMENT_CONTACT', 'PUBLIC_API', 'NO_PUBLIC_FEED_FOUND')),
    name                 VARCHAR(200) NOT NULL,
    url                  VARCHAR(1000),
    supplier_platform_id UUID REFERENCES supplier_platforms (id),
    description          TEXT,
    verification_status  VARCHAR(30)  NOT NULL DEFAULT 'PENDING_VERIFICATION'
        CHECK (verification_status IN ('PENDING_VERIFICATION', 'VERIFIED', 'UNREACHABLE', 'BLOCKED')),
    last_verified_at     TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_procurement_channels_org ON procurement_channels (organisation_id);

CREATE TABLE procurement_contacts (
    id               UUID PRIMARY KEY,
    organisation_id  UUID         NOT NULL REFERENCES procurement_organisations (id),
    name             VARCHAR(200),
    role_title       VARCHAR(200),
    email            VARCHAR(320),
    phone            VARCHAR(40),
    source_url       VARCHAR(1000),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_procurement_contacts_org ON procurement_contacts (organisation_id);

CREATE TABLE source_adapters (
    code         VARCHAR(40)  PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    description  VARCHAR(1000) NOT NULL,
    implemented  BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE source_registry (
    id                       UUID PRIMARY KEY,
    name                     VARCHAR(300) NOT NULL,
    organisation_id          UUID REFERENCES procurement_organisations (id),
    source_url               VARCHAR(1000),
    source_type              VARCHAR(40)  NOT NULL CHECK (source_type IN (
        'TENDER_LISTING', 'ESD_PORTAL', 'SUPPLIER_PORTAL', 'PROCUREMENT_PAGE', 'API', 'RSS_FEED',
        'DOCUMENT_LISTING', 'AGGREGATOR', 'COMPANY_WEBSITE')),
    sector                   VARCHAR(20)  NOT NULL CHECK (sector IN ('GOVERNMENT', 'MINING', 'PRIVATE', 'AGGREGATOR')),
    country                  VARCHAR(2)   NOT NULL DEFAULT 'ZA',
    industry                 VARCHAR(100),
    access_method            VARCHAR(30)  NOT NULL CHECK (access_method IN (
        'PUBLIC_HTML', 'PUBLIC_API', 'RSS', 'JS_RENDERED', 'DOCUMENT_DOWNLOAD', 'CSV_EXCEL',
        'LOGIN_REQUIRED', 'REGISTRATION_REQUIRED', 'LICENSED_FEED', 'MANUAL', 'UNKNOWN')),
    integration_status       VARCHAR(30)  NOT NULL CHECK (integration_status IN (
        'ACTIVE_PUBLIC', 'ACTIVE_API', 'AUTHORISED_INTEGRATION', 'LOGIN_REQUIRED', 'REGISTRATION_REQUIRED',
        'PENDING_VERIFICATION', 'TEMPORARILY_UNAVAILABLE', 'DISABLED', 'UNSUPPORTED')),
    enabled                  BOOLEAN      NOT NULL DEFAULT FALSE,
    authentication_required  BOOLEAN,
    publicly_accessible      BOOLEAN,
    robots_policy            VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN'
        CHECK (robots_policy IN ('UNKNOWN', 'ALLOWED', 'DISALLOWED', 'NOT_PRESENT')),
    terms_review_status      VARCHAR(20)  NOT NULL DEFAULT 'NOT_REVIEWED'
        CHECK (terms_review_status IN ('NOT_REVIEWED', 'APPROVED', 'RESTRICTED', 'PROHIBITED')),
    licence_notes            TEXT,
    rate_limit_per_minute    INT          NOT NULL DEFAULT 6 CHECK (rate_limit_per_minute BETWEEN 1 AND 120),
    adapter_type             VARCHAR(40) REFERENCES source_adapters (code),
    parser_config            JSONB,
    reliability              VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN'
        CHECK (reliability IN ('UNKNOWN', 'LOW', 'MEDIUM', 'HIGH')),
    priority                 INT          NOT NULL DEFAULT 100,
    verification_notes       TEXT,
    last_verified_at         TIMESTAMPTZ,
    last_attempted_crawl_at  TIMESTAMPTZ,
    last_successful_crawl_at TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    created_by               UUID,
    updated_by               UUID,
    version                  BIGINT       NOT NULL DEFAULT 0,
    -- A source may only be crawled when its access has been established as permitted.
    CONSTRAINT ck_source_enabled_requires_permitted_access CHECK (
        NOT enabled OR (integration_status IN ('ACTIVE_PUBLIC', 'ACTIVE_API', 'AUTHORISED_INTEGRATION')
                        AND terms_review_status <> 'PROHIBITED'
                        AND robots_policy <> 'DISALLOWED'
                        AND adapter_type IS NOT NULL
                        AND source_url IS NOT NULL))
);
CREATE UNIQUE INDEX ux_source_registry_name ON source_registry (LOWER(name));
CREATE INDEX ix_source_registry_org ON source_registry (organisation_id);
CREATE INDEX ix_source_registry_status ON source_registry (integration_status);

CREATE TABLE crawl_schedules (
    id                    UUID PRIMARY KEY,
    source_id             UUID        NOT NULL UNIQUE REFERENCES source_registry (id) ON DELETE CASCADE,
    interval_minutes      INT         NOT NULL DEFAULT 1440 CHECK (interval_minutes >= 15),
    next_run_at           TIMESTAMPTZ,
    consecutive_failures  INT         NOT NULL DEFAULT 0,
    backoff_until         TIMESTAMPTZ,
    lease_owner           VARCHAR(100),
    lease_until           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_crawl_schedules_next_run ON crawl_schedules (next_run_at);
