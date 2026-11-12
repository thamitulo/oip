# Source register

Seeded in `V4__seed_reference_and_sources.sql`. Observations were made with automated HTTP checks on
**2026-11-11**. URLs were only recorded if they were supplied in the specification or observed directly.
Where no official page could be verified, the company website is stored and the source is
`PENDING_VERIFICATION`.

## Enabled

| Source | Access | Evidence |
|---|---|---|
| eTenders (National Treasury) — portal feed `https://www.etenders.gov.za/Home/PaginatedTenderOpportunities` | Public JSON, `ACTIVE_API`, terms `APPROVED` | Feed behind the portal's own tender search: all ~2,080 published tenders with e-submission flag, delivery address, province, briefing and contacts. No robots.txt (404); only a privacy statement; the equivalent OCDS API declares PDDL 1.0. Live crawl: 2,084 found, 0 rejected |
| Tharisa opportunity portal `https://tharisa.geeklab.host/tenders` | `ACTIVE_PUBLIC`, robots allows all, terms `APPROVED` | Terms review (2026-11-11): the only linked terms are supplier purchasing T&Cs, with no restriction on automated access. 115 RFQs ingested (all closed on 2026-11-11). Submission is online via the portal (stated on each opportunity page) |
| DLRRD state farms to lease `https://www.dlrrd.gov.za/index.php/component/content/article/235-application-to-lease-state-farms?catid=79&Itemid=437` | `ACTIVE_PUBLIC`, robots.txt 404, terms `APPROVED` (no terms published) | Public list of state-farm lease adverts (PDF) with closing dates, ingested as `LAND_LEASE`. The site omits its issuing CA from the TLS handshake; the crawler supplies the reviewed public intermediate for dlrrd.gov.za only (see SECURITY.md). Live crawl 2026-11-12: 184 adverts, 0 rejected, 6 open |

## Not usable without permission

| Source | Status | Reason |
|---|---|---|
| Exxaro available tenders `https://www.exxaro.com/suppliers` | `ACTIVE_PUBLIC`, terms **`PROHIBITED`**, disabled | exxaro.com/disclaimer: private and non-commercial use only, no redistribution, and no linking to any page without prior written permission from Exxaro's Corporate Communication Manager. Request written permission before enabling |

## Needs attention

| Source | Status | Finding |
|---|---|---|
| Implats tender page | `TEMPORARILY_UNAVAILABLE` | HTTP 200 containing a server database error; no listings |
| Sibanye-Stillwater | `PENDING_VERIFICATION` | Site serves an Imperva/Incapsula bot challenge to automated requests; tenders page URL not verified |
| Thungela | `PENDING_VERIFICATION` | Same bot protection |
| South32 | `PENDING_VERIFICATION` | HTTP 403 |
| West Wits procurement | `PENDING_VERIFICATION` | HTTP 429 |
| Bushveld procurement portal | `PENDING_VERIFICATION` | Connection failed |
| Seriti ESD portal | `PENDING_VERIFICATION` | Angular app on Salesforce Sites with account features; no public data endpoint found. Needs an authorised integration or permission for browser rendering |
| Vedanta Zinc International | `PENDING_VERIFICATION` | Official website not confirmed (candidate domain did not respond); no URL stored |
| Harmony procurement portal | `REGISTRATION_REQUIRED` | Vendor registration and EOI portal; sourcing events visible only to registered vendors |
| Northam procurement | `REGISTRATION_REQUIRED` | Links to an Accenture-hosted ESD supplier platform; no public listing |
| Valterra Platinum suppliers | `REGISTRATION_REQUIRED` | Supplier registration information |
| Anglo American (SAP Ariba), Gold Fields (B1SA), Tronox (Coupa), Glencore, Sasol, Pan African, DRDGOLD, ARM, Petra | `PENDING_VERIFICATION` | Company website stored; official procurement pages not yet verified |

Bot protection, logins and supplier invitations are **not bypassed**. Such sources need manual verification,
an official API/licensed feed, or an authorised supplier account integration.

## Aggregators

| Aggregator | Finding |
|---|---|
| Tenders SA | Ambiguous: `tenderssa.co.za` ("TendersSA") and `tenders-sa.org` ("Tenders SA") are different services; no URL stored |
| Leads2Business | Candidate domain did not respond; subscription service |
| TenderFlow | `tenderflow.co.za` describes itself as tender-management software; feed availability unknown |
| Online Tenders | `onlinetenders.co.za`; subscription content |
| Global Tenders | `globaltenders.com`; subscription content |

All aggregators are `PENDING_VERIFICATION` and may only be integrated through official APIs, licensed feeds
or authorised accounts.

## Organisation data

- 22 mining companies with verified website status (`VERIFIED`, `BLOCKED`, `UNVERIFIED`) and primary commodities (general reference data, editable).
- Supplier platforms: CSD (`https://secure.csd.gov.za`, reachable), eTenders, SAP Ariba, Coupa, B1SA, Salesforce Sites, Fabric Workflows.
- Government procuring entities are created automatically from eTenders releases; their organisation type is inferred from the name and labelled as such.
