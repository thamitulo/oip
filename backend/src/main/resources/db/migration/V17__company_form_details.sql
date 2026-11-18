-- =====================================================================
-- V17: Company details used to pre-fill tender forms (SBD 1, SBD 4, SBD 6.1, MBD), directors/members, and
-- form-fill drafts that end up as bid documents.
-- =====================================================================

ALTER TABLE company_profiles
    ADD COLUMN csd_supplier_number       VARCHAR(20),
    ADD COLUMN income_tax_number         VARCHAR(20),
    ADD COLUMN uif_reference_number      VARCHAR(30),
    ADD COLUMN compensation_fund_number  VARCHAR(30),
    ADD COLUMN cidb_crs_number           VARCHAR(30),
    ADD COLUMN physical_address          VARCHAR(500),
    ADD COLUMN postal_address            VARCHAR(500),
    ADD COLUMN bbbee_certificate_number  VARCHAR(100),
    ADD COLUMN bbbee_certificate_expiry  DATE,
    ADD COLUMN bbbee_verification_agency VARCHAR(200);

-- Directors, members, trustees and shareholders (SBD 4 declaration of interest). Identity numbers are personal
-- information (POPIA): stored encrypted by the application (AES-256-GCM); only the last four digits are in clear
-- so they can be shown masked.
CREATE TABLE company_directors (
    id                       UUID PRIMARY KEY,
    company_profile_id       UUID         NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    full_name                VARCHAR(200) NOT NULL,
    role                     VARCHAR(20)  NOT NULL CHECK (role IN ('DIRECTOR', 'MEMBER', 'SHAREHOLDER', 'TRUSTEE')),
    id_type                  VARCHAR(20)  CHECK (id_type IN ('SA_ID', 'PASSPORT')),
    id_number_enc            VARCHAR(500),
    id_number_last4          VARCHAR(4),
    income_tax_number        VARCHAR(20),
    shareholding_percentage  NUMERIC(5, 2) CHECK (shareholding_percentage BETWEEN 0 AND 100),
    in_service_of_state      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_company_directors_profile ON company_directors (company_profile_id);

-- A tender form being filled for one pipeline item: the proposed and edited placements, then the generated PDF.
CREATE TABLE form_fills (
    id                     UUID PRIMARY KEY,
    tenant_id              UUID          NOT NULL REFERENCES tenants (id),
    tenant_opportunity_id  UUID          NOT NULL REFERENCES tenant_opportunities (id) ON DELETE CASCADE,
    source_document_id     UUID          REFERENCES source_documents (id) ON DELETE SET NULL,
    storage_key            VARCHAR(500)  NOT NULL,
    file_name              VARCHAR(300)  NOT NULL,
    page_count             INTEGER       NOT NULL,
    status                 VARCHAR(20)   NOT NULL CHECK (status IN ('DRAFT', 'GENERATED')),
    placements             JSONB         NOT NULL DEFAULT '[]',
    ocr_pages              INTEGER       NOT NULL DEFAULT 0,
    warnings               JSONB         NOT NULL DEFAULT '[]',
    bid_document_id        UUID          REFERENCES bid_documents (id) ON DELETE SET NULL,
    created_by             UUID          NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_form_fills_item ON form_fills (tenant_id, tenant_opportunity_id);
