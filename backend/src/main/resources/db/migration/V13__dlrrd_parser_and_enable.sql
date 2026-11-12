-- DLRRD state-farm listing: parser configuration tested against the page markup (2026-11-12).
--  * items are leaf <li> elements only (the 2023/2024 sections wrap their lists in an extra <li>);
--  * closing dates also in the forms "13th of July 2026", "1st of August 2026", "Closing on the 22 December 2023"
--    and "Closing19 August 2024";
--  * province spellings "KwaZulu- Natal", "Kwazulu-Natal" and "KwaZulu Natal".
-- The source stays disabled: it is still blocked on TLS (see V12).
UPDATE source_registry SET parser_config = '{"listContainerSelector": "div[property=text] ul",
    "itemSelector": "div[property=text] li:not(:has(li)):has(a[href~=(?i)\\.pdf$])",
    "fields": {"title": {"selector": "a"},
               "documentUrl": {"selector": "a", "attr": "href"},
               "closingDate": {"selector": "a", "joinGroups": true,
                               "regex": "(?i)closing(?:\\s+date)?(?:\\s+on)?(?:\\s+the)?\\s*:?\\s*(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?\\s+([A-Za-z]+\\s+\\d{4}(?:\\s+at\\s+\\d{1,2}[h:]\\d{2})?)"},
               "province": {"selector": "a", "regex": "(?i)(Eastern Cape|Free State|Gauteng|KwaZulu[-\\s]*Natal|Limpopo|Mpumalanga|North West|Northern Cape|Western Cape)"},
               "briefing": {"selector": "a", "regex": "(?i)\\(([^)]*briefing[^)]*)\\)"}},
    "requireFields": ["closingDate"],
    "dateFormats": ["d MMMM yyyy ''at'' HH''h''mm", "d MMMM yyyy ''at'' HH:mm", "d MMMM yyyy", "d MMM yyyy"],
    "defaultEventType": "LAND_LEASE"}',
    updated_at = NOW()
WHERE id = 'c0000000-0000-0000-0000-000000000040';

-- TLS: the crawler now supplies the intermediate certificate the site omits (oip.crawler.trust, for dlrrd.gov.za
-- only; chain to a JDK-trusted root and hostname still verified). Live check 2026-11-12: HTTP 200, 184 adverts parsed,
-- no item errors, robots.txt 404 (no restrictions). Enable the source.
UPDATE source_registry SET integration_status = 'ACTIVE_PUBLIC', enabled = TRUE,
    verification_notes = 'Checked 2026-11-11/12: public, server-rendered list of state farm lease adverts (PDF), each titled with farm/portion and closing date. '
        || 'www.dlrrd.gov.za omits its issuing CA (Sectigo Public Server Authentication CA OV R36) from the TLS handshake; the crawler uses a reviewed copy '
        || 'of that public intermediate for dlrrd.gov.za only (SHA-256 6542D176...E78530, from the AIA URL http://crt.sectigo.com/SectigoPublicServerAuthenticationCAOVR36.crt). '
        || 'Certificate validation is otherwise unchanged. Live check 2026-11-12: 184 adverts parsed, 0 errors.',
    last_verified_at = '2026-11-12T08:00:00Z', updated_at = NOW()
WHERE id = 'c0000000-0000-0000-0000-000000000040';
