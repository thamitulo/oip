-- =====================================================================
-- V12: State land leasing (DLRRD state farms) and user focus areas.
-- Verification observations made on 2026-11-11 with automated HTTP checks.
-- =====================================================================

INSERT INTO reference_codes (code_type, code, label, description, sort_order) VALUES
 ('EVENT_TYPE', 'LAND_LEASE', 'State land lease / allocation',
  'State-owned land (e.g. state farms) offered for lease or allocation', 11)
ON CONFLICT (code_type, code) DO NOTHING;
UPDATE reference_codes SET sort_order = 12 WHERE code_type = 'EVENT_TYPE' AND code = 'OTHER';

INSERT INTO procurement_organisations (id, name, short_name, organisation_type, sector, website, website_verification,
                                       website_verified_at, notes, created_at, updated_at) VALUES
 ('a0000000-0000-0000-0000-000000000040', 'Department of Land Reform and Rural Development', 'DLRRD',
  'NATIONAL_DEPARTMENT', 'GOVERNMENT', 'https://www.dlrrd.gov.za', 'VERIFIED', '2026-11-11T19:00:00Z',
  'Advertises state farms for lease/allocation under the State Land Lease and Disposal Policy (including PLAS farms).',
  NOW(), NOW())
ON CONFLICT DO NOTHING;

-- The department may already exist (created automatically from eTenders releases). Reuse that row and record the
-- verified details; everything below refers to the organisation by name, not by a fixed id.
UPDATE procurement_organisations SET
    short_name = 'DLRRD', organisation_type = 'NATIONAL_DEPARTMENT', sector = 'GOVERNMENT',
    website = 'https://www.dlrrd.gov.za', website_verification = 'VERIFIED', website_verified_at = '2026-11-11T19:00:00Z',
    notes = 'Advertises state farms for lease/allocation under the State Land Lease and Disposal Policy (including PLAS farms).',
    updated_at = NOW()
WHERE LOWER(name) = LOWER('Department of Land Reform and Rural Development');

INSERT INTO procurement_channels (id, organisation_id, channel_type, name, url, supplier_platform_id, description,
                                  verification_status, last_verified_at, created_at, updated_at) VALUES
 (gen_random_uuid(), (SELECT id FROM procurement_organisations WHERE LOWER(name) = LOWER('Department of Land Reform and Rural Development')), 'PUBLIC_TENDER_LISTING', 'Application to lease state farms',
  'https://www.dlrrd.gov.za/index.php/component/content/article/235-application-to-lease-state-farms?catid=79&Itemid=437', NULL,
  'List of state farm lease/allocation adverts (PDF) with closing dates. Applicants also complete the "Declaration of applicant for land allocation" form: https://www.dlrrd.gov.za/images/Docs/2023/declaration-by-land-applicant.pdf',
  'VERIFIED', '2026-11-11T19:00:00Z', NOW(), NOW());

INSERT INTO source_registry (id, name, organisation_id, source_url, source_type, sector, access_method, integration_status,
                             enabled, authentication_required, publicly_accessible, robots_policy, terms_review_status,
                             licence_notes, rate_limit_per_minute, adapter_type, parser_config, reliability, priority,
                             verification_notes, last_verified_at, created_at, updated_at) VALUES
 ('c0000000-0000-0000-0000-000000000040', 'DLRRD state farms to lease', (SELECT id FROM procurement_organisations WHERE LOWER(name) = LOWER('Department of Land Reform and Rural Development')),
  'https://www.dlrrd.gov.za/index.php/component/content/article/235-application-to-lease-state-farms?catid=79&Itemid=437',
  'DOCUMENT_LISTING', 'GOVERNMENT', 'PUBLIC_HTML', 'PENDING_VERIFICATION',
  FALSE, FALSE, TRUE, 'NOT_PRESENT', 'APPROVED',
  'Public government advert listing. On 2026-11-11 no terms of use, disclaimer or copyright page could be found on dlrrd.gov.za (/disclaimer, /terms-and-conditions, /copyright return 404) and /robots.txt returns 404. Only listing metadata and links to the official adverts are stored. Re-review if the department publishes terms.',
  6, 'HTML_LISTING',
  '{"listContainerSelector": "div[property=text] ul",
    "itemSelector": "div[property=text] li:has(a[href~=(?i)\\.pdf$])",
    "fields": {"title": {"selector": "a"},
               "documentUrl": {"selector": "a", "attr": "href"},
               "closingDate": {"selector": "a", "regex": "(?i)closing(?:\\s+date)?\\s*:?\\s*(\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4}(?:\\s+at\\s+\\d{1,2}[h:]\\d{2})?)"},
               "province": {"selector": "a", "regex": "(Eastern Cape|Free State|Gauteng|KwaZulu-Natal|Limpopo|Mpumalanga|North West|Northern Cape|Western Cape)"},
               "briefing": {"selector": "a", "regex": "(?i)\\(([^)]*briefing[^)]*)\\)"}},
    "requireFields": ["closingDate"],
    "dateFormats": ["d MMMM yyyy ''at'' HH''h''mm", "d MMMM yyyy ''at'' HH:mm", "d MMMM yyyy", "d MMM yyyy"],
    "defaultEventType": "LAND_LEASE"}',
  'MEDIUM', 15,
  'Checked 2026-11-11/12: the page is a public, server-rendered list of ~235 PDF adverts (2023 onwards), each titled with farm/portion and "Closing date" (4 open on 2026-11-11); the parser configuration was tested against the same markup. BLOCKED ON TLS: www.dlrrd.gov.za does not send its intermediate certificate (Sectigo Public Server Authentication CA OV R36), so the crawler''s Java TLS validation fails (PKIX path building failed); browsers succeed by fetching the intermediate. Certificate validation is not relaxed. To enable: the department fixes its certificate chain, or the intermediate is added to the crawler''s trust configuration after review. Then set status ACTIVE_PUBLIC and enable.',
  '2026-11-11T19:00:00Z', NOW(), NOW());

INSERT INTO crawl_schedules (id, source_id, interval_minutes, next_run_at, created_at, updated_at)
VALUES (gen_random_uuid(), 'c0000000-0000-0000-0000-000000000040', 1440, NOW(), NOW(), NOW());

-- What a user is currently focused on (e.g. "state land to lease in Limpopo"). Personal to the user, scoped to the tenant.
CREATE TABLE focus_areas (
    id              UUID PRIMARY KEY,
    tenant_id       UUID          NOT NULL REFERENCES tenants (id),
    user_id         UUID          NOT NULL REFERENCES users (id),
    name            VARCHAR(120)  NOT NULL,
    description     VARCHAR(1000),
    template_code   VARCHAR(40),
    keywords        VARCHAR(2000),
    event_types     VARCHAR(500),
    provinces       VARCHAR(500),
    sectors         VARCHAR(200),
    include_closed  BOOLEAN       NOT NULL DEFAULT FALSE,
    notify          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_focus_areas_user ON focus_areas (tenant_id, user_id);

ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type IN ('NEW_MATCH', 'CLOSING_SOON',
    'CLOSING_DATE_CHANGED', 'NEW_DOCUMENT', 'STATUS_CHANGED', 'OPPORTUNITY_CHANGED', 'ASSIGNED', 'TASK_DUE', 'SOURCE_FAILURE',
    'FOCUS_MATCH'));
