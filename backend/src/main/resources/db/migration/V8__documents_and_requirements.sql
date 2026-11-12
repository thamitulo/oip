-- =====================================================================
-- V8: Document pipeline (downloaded originals + extracted text) and
-- requirements extracted from opportunities.
-- =====================================================================

CREATE TABLE source_documents (
    id                       UUID PRIMARY KEY,
    opportunity_document_id  UUID          NOT NULL UNIQUE REFERENCES opportunity_documents (id) ON DELETE CASCADE,
    opportunity_id           UUID          NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    status                   VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING', 'EXTRACTED', 'FAILED', 'SKIPPED')),
    content_type             VARCHAR(200),
    size_bytes               BIGINT,
    checksum_sha256          VARCHAR(64),
    storage_key              VARCHAR(500),
    page_count               INT,
    text_content             TEXT,
    text_truncated           BOOLEAN       NOT NULL DEFAULT FALSE,
    error                    VARCHAR(2000),
    attempts                 INT           NOT NULL DEFAULT 0,
    next_attempt_at          TIMESTAMPTZ,
    downloaded_at            TIMESTAMPTZ,
    extracted_at             TIMESTAMPTZ,
    original_deleted_at      TIMESTAMPTZ,
    created_at               TIMESTAMPTZ   NOT NULL,
    updated_at               TIMESTAMPTZ   NOT NULL,
    text_vector              TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', left(coalesce(text_content, ''), 500000))) STORED
);
CREATE INDEX ix_source_documents_opportunity ON source_documents (opportunity_id);
CREATE INDEX ix_source_documents_status ON source_documents (status, next_attempt_at);
CREATE INDEX ix_source_documents_text ON source_documents USING GIN (text_vector);

-- Requirements found for an opportunity, each with its provenance and the evidence it was derived from.
CREATE TABLE opportunity_requirements (
    id                 UUID PRIMARY KEY,
    opportunity_id     UUID          NOT NULL REFERENCES opportunities (id) ON DELETE CASCADE,
    requirement_type   VARCHAR(40)   NOT NULL CHECK (requirement_type IN (
        'CIDB_GRADING', 'BBBEE_LEVEL', 'PREFERENCE_POINT_SYSTEM', 'CSD_REGISTRATION', 'TAX_COMPLIANCE',
        'PROFESSIONAL_REGISTRATION', 'CERTIFICATION', 'COMPULSORY_BRIEFING', 'LOCAL_CONTENT', 'EXPERIENCE',
        'GEOGRAPHIC_RESTRICTION', 'OTHER')),
    value              VARCHAR(500),
    mandatory          BOOLEAN,
    provenance         VARCHAR(20)   NOT NULL CHECK (provenance IN ('SOURCE', 'SOURCE_POLICY', 'DOCUMENT', 'AI', 'HUMAN')),
    confidence         NUMERIC(4, 3),
    evidence           VARCHAR(2000),
    source_document_id UUID REFERENCES source_documents (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_opportunity_requirements_opportunity ON opportunity_requirements (opportunity_id);
CREATE INDEX ix_opportunity_requirements_type ON opportunity_requirements (requirement_type, value);

-- Closing dates can also come from documents (sources such as advert-only listings publish them only in PDFs).
ALTER TABLE opportunities ADD COLUMN closing_provenance VARCHAR(20)
    CHECK (closing_provenance IN ('SOURCE', 'SOURCE_POLICY', 'DOCUMENT', 'AI', 'HUMAN'));
UPDATE opportunities SET closing_provenance = 'SOURCE' WHERE closing_date IS NOT NULL;

-- Company documents gain extracted text (capability statements feed matching).
ALTER TABLE company_documents
    ADD COLUMN text_content TEXT,
    ADD COLUMN extraction_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (extraction_status IN ('PENDING', 'EXTRACTED', 'FAILED', 'SKIPPED')),
    ADD COLUMN description VARCHAR(500);
