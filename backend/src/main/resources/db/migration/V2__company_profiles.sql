-- =====================================================================
-- V2: Tenant company profiles. Every row carries tenant_id.
-- Self-declared information carries a verification_status so the platform
-- never treats a claim as verified fact.
-- =====================================================================

CREATE TABLE company_profiles (
    id                           UUID PRIMARY KEY,
    tenant_id                    UUID         NOT NULL UNIQUE REFERENCES tenants (id),
    legal_name                   VARCHAR(300) NOT NULL,
    trading_name                 VARCHAR(300),
    registration_number          VARCHAR(50),
    vat_number                   VARCHAR(30),
    website                      VARCHAR(500),
    description                  TEXT,
    province                     VARCHAR(40),
    city                         VARCHAR(120),
    business_size                VARCHAR(20) CHECK (business_size IN ('MICRO', 'SMALL', 'MEDIUM', 'LARGE')),
    annual_turnover_band         VARCHAR(40),
    bbbee_level                  VARCHAR(20) CHECK (bbbee_level IN ('LEVEL_1','LEVEL_2','LEVEL_3','LEVEL_4','LEVEL_5','LEVEL_6','LEVEL_7','LEVEL_8','NON_COMPLIANT','UNKNOWN')),
    black_ownership_percentage   NUMERIC(5, 2) CHECK (black_ownership_percentage BETWEEN 0 AND 100),
    enterprise_classification    VARCHAR(20) CHECK (enterprise_classification IN ('EME', 'QSE', 'GENERIC', 'UNKNOWN')),
    cidb_grading                 VARCHAR(20),
    mining_sector_experience     TEXT,
    government_sector_experience TEXT,
    contact_name                 VARCHAR(200),
    contact_email                VARCHAR(320),
    contact_phone                VARCHAR(40),
    created_at                   TIMESTAMPTZ  NOT NULL,
    updated_at                   TIMESTAMPTZ  NOT NULL,
    created_by                   UUID,
    updated_by                   UUID,
    version                      BIGINT       NOT NULL DEFAULT 0
);

-- Simple named classification lists -----------------------------------
CREATE TABLE company_categories (     -- industries the company serves
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants (id),
    company_profile_id UUID NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL
);
CREATE TABLE company_services (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants (id),
    company_profile_id UUID NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL
);
CREATE TABLE company_products (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants (id),
    company_profile_id UUID NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL
);
CREATE TABLE company_commodities (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants (id),
    company_profile_id UUID NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL
);
CREATE TABLE company_regions (        -- operating regions (province codes or NATIONAL)
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants (id),
    company_profile_id UUID NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL
);
CREATE INDEX ix_company_categories_profile ON company_categories (company_profile_id);
CREATE INDEX ix_company_services_profile ON company_services (company_profile_id);
CREATE INDEX ix_company_products_profile ON company_products (company_profile_id);
CREATE INDEX ix_company_commodities_profile ON company_commodities (company_profile_id);
CREATE INDEX ix_company_regions_profile ON company_regions (company_profile_id);

-- Structured claims ------------------------------------------------------
CREATE TABLE company_certifications (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants (id),
    company_profile_id UUID NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    name                VARCHAR(200) NOT NULL,
    issuing_body        VARCHAR(200),
    certificate_number  VARCHAR(100),
    valid_until         DATE,
    verification_status VARCHAR(30) NOT NULL DEFAULT 'SELF_DECLARED'
        CHECK (verification_status IN ('SELF_DECLARED', 'DOCUMENT_PROVIDED', 'VERIFIED', 'EXPIRED'))
);
CREATE INDEX ix_company_certifications_profile ON company_certifications (company_profile_id);

CREATE TABLE company_registrations (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants (id),
    company_profile_id UUID NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    registration_type   VARCHAR(40) NOT NULL
        CHECK (registration_type IN ('CSD', 'CIDB', 'SUPPLIER_PORTAL', 'PROFESSIONAL_BODY', 'TAX', 'OTHER')),
    registration_body   VARCHAR(200) NOT NULL,
    registration_number VARCHAR(100),
    valid_until         DATE,
    verification_status VARCHAR(30) NOT NULL DEFAULT 'SELF_DECLARED'
        CHECK (verification_status IN ('SELF_DECLARED', 'DOCUMENT_PROVIDED', 'VERIFIED', 'EXPIRED'))
);
CREATE INDEX ix_company_registrations_profile ON company_registrations (company_profile_id);

CREATE TABLE company_capabilities (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants (id),
    company_profile_id UUID NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    name             VARCHAR(200) NOT NULL,
    description      TEXT,
    years_experience INT CHECK (years_experience >= 0)
);
CREATE INDEX ix_company_capabilities_profile ON company_capabilities (company_profile_id);

CREATE TABLE company_references (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants (id),
    company_profile_id UUID NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    client_name     VARCHAR(300) NOT NULL,
    project_title   VARCHAR(300) NOT NULL,
    description     TEXT,
    sector          VARCHAR(40) CHECK (sector IN ('GOVERNMENT', 'MINING', 'PRIVATE', 'OTHER')),
    contract_value  NUMERIC(18, 2),
    year_completed  INT
);
CREATE INDEX ix_company_references_profile ON company_references (company_profile_id);

-- Table is created now; the upload pipeline lands with the documents module (Phase 5).
CREATE TABLE company_documents (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants (id),
    company_profile_id UUID NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    document_type    VARCHAR(40) NOT NULL,
    file_name        VARCHAR(300) NOT NULL,
    content_type     VARCHAR(150) NOT NULL,
    size_bytes       BIGINT NOT NULL,
    storage_key      VARCHAR(500) NOT NULL,
    checksum_sha256  VARCHAR(64) NOT NULL,
    uploaded_by      UUID NOT NULL,
    uploaded_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_company_documents_profile ON company_documents (company_profile_id);
