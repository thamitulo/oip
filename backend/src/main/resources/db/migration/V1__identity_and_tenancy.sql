-- =====================================================================
-- V1: Identity, tenancy, subscriptions, audit and system settings
-- All timestamps are stored in UTC (timestamptz).
-- =====================================================================

CREATE TABLE roles (
    code        VARCHAR(40) PRIMARY KEY,
    scope       VARCHAR(20) NOT NULL CHECK (scope IN ('PLATFORM', 'TENANT')),
    description VARCHAR(255) NOT NULL,
    rank        INT NOT NULL
);

CREATE TABLE permissions (
    code        VARCHAR(80) PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE role_permissions (
    role_code       VARCHAR(40) NOT NULL REFERENCES roles (code),
    permission_code VARCHAR(80) NOT NULL REFERENCES permissions (code),
    PRIMARY KEY (role_code, permission_code)
);

CREATE TABLE users (
    id                    UUID PRIMARY KEY,
    email                 VARCHAR(320) NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    full_name             VARCHAR(200) NOT NULL,
    timezone              VARCHAR(64)  NOT NULL DEFAULT 'Africa/Johannesburg',
    platform_admin        BOOLEAN      NOT NULL DEFAULT FALSE,
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    terms_accepted_at     TIMESTAMPTZ,
    marketing_consent     BOOLEAN      NOT NULL DEFAULT FALSE,
    marketing_consent_at  TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    created_by            UUID,
    updated_by            UUID,
    version               BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_users_email_lower ON users (LOWER(email));

CREATE TABLE tenants (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    slug        VARCHAR(100) NOT NULL UNIQUE,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    timezone    VARCHAR(64)  NOT NULL DEFAULT 'Africa/Johannesburg',
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE tenant_users (
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenants (id),
    user_id     UUID        NOT NULL REFERENCES users (id),
    role_code   VARCHAR(40) NOT NULL REFERENCES roles (code),
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ux_tenant_users UNIQUE (tenant_id, user_id)
);
CREATE INDEX ix_tenant_users_user ON tenant_users (user_id);

CREATE TABLE user_sessions (
    id                  UUID PRIMARY KEY,
    user_id             UUID         NOT NULL REFERENCES users (id),
    tenant_id           UUID         NOT NULL REFERENCES tenants (id),
    refresh_token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    user_agent          VARCHAR(500),
    ip_address          VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    revoked_at          TIMESTAMPTZ,
    replaced_by         UUID
);
CREATE INDEX ix_user_sessions_user ON user_sessions (user_id);

CREATE TABLE subscription_plans (
    id           UUID PRIMARY KEY,
    code         VARCHAR(40)  NOT NULL UNIQUE,
    name         VARCHAR(100) NOT NULL,
    description  VARCHAR(500),
    max_users    INT,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE tenant_subscriptions (
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenants (id),
    plan_id     UUID        NOT NULL REFERENCES subscription_plans (id),
    status      VARCHAR(20) NOT NULL CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'CANCELLED')),
    started_at  TIMESTAMPTZ NOT NULL,
    ends_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_tenant_subscriptions_tenant ON tenant_subscriptions (tenant_id);

CREATE TABLE audit_events (
    id             UUID PRIMARY KEY,
    tenant_id      UUID,
    actor_user_id  UUID,
    action         VARCHAR(100) NOT NULL,
    entity_type    VARCHAR(100),
    entity_id      VARCHAR(100),
    details        JSONB,
    ip_address     VARCHAR(64),
    occurred_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_audit_events_tenant_time ON audit_events (tenant_id, occurred_at DESC);
CREATE INDEX ix_audit_events_entity ON audit_events (entity_type, entity_id);

CREATE TABLE system_settings (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value TEXT         NOT NULL,
    description   VARCHAR(500),
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    UUID
);

-- ---------------------------------------------------------------------
-- Reference data
-- ---------------------------------------------------------------------
INSERT INTO roles (code, scope, description, rank) VALUES
 ('PLATFORM_ADMIN',      'PLATFORM', 'Operates the platform: sources, crawlers, system administration', 100),
 ('TENANT_OWNER',        'TENANT',   'Owns the subscribing business account',                            90),
 ('TENANT_ADMIN',        'TENANT',   'Administers users and settings for the business',                  80),
 ('PROCUREMENT_MANAGER', 'TENANT',   'Manages procurement focus and company profile',                    70),
 ('BID_MANAGER',         'TENANT',   'Runs the bid pipeline and go/no-go decisions',                     60),
 ('BID_RESEARCHER',      'TENANT',   'Researches and qualifies opportunities',                           50),
 ('STANDARD_USER',       'TENANT',   'Works on assigned opportunities',                                  40),
 ('READ_ONLY_USER',      'TENANT',   'Read-only access to tenant data',                                  10);

INSERT INTO permissions (code, description) VALUES
 ('TENANT_MANAGE',        'Update tenant settings'),
 ('USERS_MANAGE',         'Invite users and change roles'),
 ('COMPANY_PROFILE_EDIT', 'Edit the company profile'),
 ('PIPELINE_EDIT',        'Change pipeline status, notes, tasks and assignments'),
 ('OPPORTUNITIES_VIEW',   'View opportunities'),
 ('SOURCES_MANAGE',       'Manage procurement sources and crawls');

INSERT INTO role_permissions (role_code, permission_code) VALUES
 ('PLATFORM_ADMIN', 'SOURCES_MANAGE'), ('PLATFORM_ADMIN', 'OPPORTUNITIES_VIEW'),
 ('TENANT_OWNER', 'TENANT_MANAGE'), ('TENANT_OWNER', 'USERS_MANAGE'), ('TENANT_OWNER', 'COMPANY_PROFILE_EDIT'),
 ('TENANT_OWNER', 'PIPELINE_EDIT'), ('TENANT_OWNER', 'OPPORTUNITIES_VIEW'),
 ('TENANT_ADMIN', 'TENANT_MANAGE'), ('TENANT_ADMIN', 'USERS_MANAGE'), ('TENANT_ADMIN', 'COMPANY_PROFILE_EDIT'),
 ('TENANT_ADMIN', 'PIPELINE_EDIT'), ('TENANT_ADMIN', 'OPPORTUNITIES_VIEW'),
 ('PROCUREMENT_MANAGER', 'COMPANY_PROFILE_EDIT'), ('PROCUREMENT_MANAGER', 'PIPELINE_EDIT'), ('PROCUREMENT_MANAGER', 'OPPORTUNITIES_VIEW'),
 ('BID_MANAGER', 'PIPELINE_EDIT'), ('BID_MANAGER', 'OPPORTUNITIES_VIEW'),
 ('BID_RESEARCHER', 'PIPELINE_EDIT'), ('BID_RESEARCHER', 'OPPORTUNITIES_VIEW'),
 ('STANDARD_USER', 'PIPELINE_EDIT'), ('STANDARD_USER', 'OPPORTUNITIES_VIEW'),
 ('READ_ONLY_USER', 'OPPORTUNITIES_VIEW');

-- Commercial plans are a business decision that has not been made yet; a single
-- default plan exists so that every tenant has a subscription record.
INSERT INTO subscription_plans (id, code, name, description, max_users, active, created_at, updated_at) VALUES
 ('00000000-0000-0000-0000-000000000001', 'DEFAULT', 'Default plan',
  'Placeholder plan used until commercial plans are defined', NULL, TRUE, NOW(), NOW());

INSERT INTO system_settings (setting_key, setting_value, description, updated_at) VALUES
 ('default.timezone', 'Africa/Johannesburg', 'Default display timezone for South African sources and users', NOW());
