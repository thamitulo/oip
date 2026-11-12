-- =====================================================================
-- V7: Submission method, briefing details, mining relevance, and the
-- outcome of the source/terms review carried out on 2026-11-11.
-- =====================================================================

ALTER TABLE opportunities
    -- How bids must be submitted. NOT_SPECIFIED when the source does not say.
    ADD COLUMN submission_method     VARCHAR(30) NOT NULL DEFAULT 'NOT_SPECIFIED'
        CHECK (submission_method IN ('ELECTRONIC', 'PHYSICAL', 'ELECTRONIC_OR_PHYSICAL', 'NOT_SPECIFIED')),
    ADD COLUMN submission_channel    VARCHAR(300),
    ADD COLUMN submission_address    VARCHAR(1000),
    -- SOURCE: a structured field on the record; SOURCE_POLICY: stated by the source for all its listings;
    -- DOCUMENT: deterministic extraction from text; AI: model inference; HUMAN: manual correction.
    ADD COLUMN submission_provenance VARCHAR(20)
        CHECK (submission_provenance IN ('SOURCE', 'SOURCE_POLICY', 'DOCUMENT', 'AI', 'HUMAN')),
    ADD COLUMN submission_evidence   VARCHAR(2000),
    ADD COLUMN two_envelope          BOOLEAN,
    ADD COLUMN briefing_at           TIMESTAMPTZ,
    ADD COLUMN briefing_venue        VARCHAR(1000),
    ADD COLUMN special_conditions    TEXT,
    -- Public-sector tenders whose subject is mining (rule-based, evidence kept). Mining-company listings are
    -- identified by source/organisation sector instead.
    ADD COLUMN mining_related        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN mining_evidence       VARCHAR(500);

CREATE INDEX ix_opportunities_mining_related ON opportunities (mining_related) WHERE mining_related;
CREATE INDEX ix_opportunities_submission ON opportunities (submission_method);

INSERT INTO source_adapters (code, name, description, implemented) VALUES
 ('ETENDERS_PORTAL', 'eTenders portal feed',
  'Published tenders from the eTenders portal JSON feed (all current tenders, with e-submission flag, delivery address, province and briefing details)',
  TRUE);

-- eTenders: switch to the portal feed, which covers all ~2,000 published tenders (the OCDS API only a date
-- window) and carries submission method, province and briefing details. Same source and identifiers
-- (ocds-9t57fa-{id}), so existing records are continued and enriched, not duplicated.
UPDATE source_registry SET
    name = 'eTenders (National Treasury)',
    source_url = 'https://www.etenders.gov.za/Home/PaginatedTenderOpportunities',
    source_type = 'TENDER_LISTING',
    access_method = 'PUBLIC_API',
    adapter_type = 'ETENDERS_PORTAL',
    parser_config = '{"pageSize": 100, "maxPages": 40, "ocidPrefix": "ocds-9t57fa-"}',
    licence_notes = 'Public government tender publication. The equivalent OCDS API declares the PDDL 1.0 open data licence. www.etenders.gov.za has no robots.txt (HTTP 404) and publishes only a privacy statement (no terms restricting access) as of 2026-11-11.',
    verification_notes = 'Verified 2026-11-11: the portal''s public JSON feed returns all published tenders (2,083) with eSubmission flag, delivery address, province, compulsory briefing and contact details. Closing times are published without a timezone (SAST), which confirms the OCDS API''s "Z" times are SAST wall-clock times. The OCDS adapter (ETENDERS_OCDS) remains available as an alternative.',
    last_verified_at = '2026-11-11T18:00:00Z',
    updated_at = NOW()
WHERE id = 'c0000000-0000-0000-0000-000000000001';

-- Tharisa: terms reviewed. The only terms document linked from the portal is Tharisa's supplier purchasing
-- terms and conditions, which does not restrict automated access; robots.txt allows all agents.
UPDATE source_registry SET
    terms_review_status = 'APPROVED',
    enabled = TRUE,
    licence_notes = 'Portal links only supplier purchasing terms (tharisa-terms-and-conditions.pdf), which contain no restriction on automated access or listing metadata. Only listing metadata is stored, with a link to each original.',
    parser_config = '{"listContainerSelector": "table.public-opportunity-table", "itemSelector": "table.public-opportunity-table tbody tr",
      "fields": {"referenceNumber": {"selector": "td[data-label=Reference]"},
                 "title": {"selector": "td[data-label=Title] a"},
                 "detailUrl": {"selector": "td[data-label=Title] a", "attr": "href"},
                 "status": {"selector": "td[data-label=Status]"},
                 "location": {"selector": "td[data-label=Location]"},
                 "category": {"selector": "td[data-label=Category]"},
                 "closingDate": {"selector": "td[data-label=Closes]"}},
      "dateFormats": ["d MMM yyyy"], "defaultEventType": "RFQ",
      "statusMapping": {"open": "OPEN", "closed": "CLOSED", "awarded": "AWARDED"},
      "submission": {"method": "ELECTRONIC", "channel": "Online application on the Tharisa opportunity portal",
                     "evidence": "Each opportunity page offers \"Apply for Tender\", which opens the portal''s online application wizard (observed 2026-11-11)."}}',
    verification_notes = 'Verified 2026-11-11: server-rendered table, robots.txt allows all. Terms reviewed and approved for listing metadata. On 2026-11-11 all 115 listed RFQs were closed.',
    last_verified_at = '2026-11-11T18:00:00Z',
    updated_at = NOW()
WHERE id = 'c0000000-0000-0000-0000-000000000002';

-- Exxaro: website disclaimer permits only private, non-commercial use, forbids redistribution and forbids
-- linking to any page without written permission. Not usable without Exxaro's written permission.
UPDATE source_registry SET
    terms_review_status = 'PROHIBITED',
    enabled = FALSE,
    licence_notes = 'exxaro.com/disclaimer (reviewed 2026-11-11): content may be used for private and non-commercial purposes only; redistribution is prohibited; linking to any page requires prior written permission from Exxaro''s Corporate Communication Manager. Obtain written permission before enabling.',
    updated_at = NOW()
WHERE id = 'c0000000-0000-0000-0000-000000000003';

-- Northam and Harmony are supplier registration portals, not public listings.
UPDATE source_registry SET
    integration_status = 'REGISTRATION_REQUIRED', access_method = 'REGISTRATION_REQUIRED',
    verification_notes = 'Checked 2026-11-11: the procurement page links to a supplier/ESD registration platform (accenture-esdplatform.my.site.com); no public opportunity listing.',
    last_verified_at = '2026-11-11T18:00:00Z', updated_at = NOW()
WHERE id = 'c0000000-0000-0000-0000-000000000010';
UPDATE source_registry SET
    integration_status = 'REGISTRATION_REQUIRED', access_method = 'REGISTRATION_REQUIRED',
    verification_notes = 'Checked 2026-11-11: vendor registration and expression-of-interest portal; sourcing events are visible to registered vendors only.',
    last_verified_at = '2026-11-11T18:00:00Z', updated_at = NOW()
WHERE id = 'c0000000-0000-0000-0000-000000000008';
UPDATE source_registry SET
    verification_notes = 'Checked 2026-11-11: Angular single-page application on Salesforce Sites with account features; no public data endpoint found. Needs an authorised integration or permission for browser rendering.',
    updated_at = NOW()
WHERE id = 'c0000000-0000-0000-0000-000000000005';

INSERT INTO procurement_channels (id, organisation_id, channel_type, name, url, supplier_platform_id, description,
                                  verification_status, last_verified_at, created_at, updated_at) VALUES
 (gen_random_uuid(), 'a0000000-0000-0000-0000-000000000006', 'SUPPLIER_REGISTRATION', 'Northam ESD supplier platform',
  'https://accenture-esdplatform.my.site.com/suppliers/s/?language=en_ZA', NULL,
  'Linked from northam.co.za/procurement (observed 2026-11-11).', 'VERIFIED', '2026-11-11T18:00:00Z', NOW(), NOW());
UPDATE procurement_channels SET verification_status = 'VERIFIED', last_verified_at = '2026-11-11T18:00:00Z',
    description = 'Vendor registration and expressions of interest; sourcing events visible to registered vendors.'
WHERE name = 'Harmony procurement portal';
