# Implementation status

Last updated: 2026-11-18.

Verification: backend **126 tests** (2026-11-18) (unit + Testcontainers/PostgreSQL 17 integration; the opt-in live check and
the Tesseract OCR test are skipped where Tesseract is not installed; the OCR test passes in a container with it). The
form-filling flow was also run end to end in the Docker stack with a digital and a scanned SBD 1/SBD 4 test form, frontend **16 tests** (Vitest), clean production frontend build (2026-11-18), and on 2026-11-13 a Docker Compose stack (postgres +
backend + nginx frontend + mailpit) healthy, and an end-to-end smoke run through nginx. The smoke run covered
cookie login/restore, the dashboard, the mining view, recommended matches, extraction of a real eTenders PDF,
shortlisting, notifications, focus templates and the frontend routes.

Live catalogue (2026-11-12): 2,096 active opportunities, 18 mining-related. Submission method: 1,899 physical,
182 electronic, 15 not specified.

## Phase 1 — Foundation ✅

- [x] Spring Boot 4.1.2 / Java 21 project, Angular 22 project
- [x] PostgreSQL + Flyway migrations (V1–V17), Docker Compose
- [x] Registration, login, refresh rotation with reuse detection, logout, auth rate limiting
- [x] Refresh token in an httpOnly `SameSite=Strict` cookie; access token kept in memory only
- [x] Tenants, roles/permissions, member management with role-hierarchy rules
- [x] Company profile (all spec fields; claims carry verification status; users cannot self-verify)
- [x] Company document upload with text extraction
- [x] OpenAPI/Swagger, tenant isolation tests
- [x] Team invitations by e-mail (people set their own password), password reset ("Forgot password"),
      e-mail verification with a reminder banner; single-use hashed tokens

## Phase 2 — Procurement source registry ✅

- [x] Organisations, mining companies, operations, supplier platforms, procurement channels, contacts
- [x] Source registry with spec metadata, integration statuses, terms-review status; DB constraint on enabling
- [x] Seeded sources with recorded verification evidence (see SOURCE_REGISTER.md)
- [x] Admin UI: list, create, edit, enable/disable, verification status, health
- [x] Admin UI to create/edit organisations and add/edit/remove procurement channels (platform admins)
- [x] Admin UI for mining operations (mines, plants, sites) on mining-sector organisation pages

## Phase 3 — Crawler engine ✅

- [x] Adapters: eTenders portal feed (primary), eTenders OCDS, HTML listing, JSON API, RSS/Atom, CSV
- [x] Scheduled (lease-safe) and manual crawls, dry runs, robots.txt, polite rate limiting, backoff
- [x] De-duplication, cross-source linking, change detection with history, expiry
- [x] Submission method (electronic/physical) with channel, address, provenance and evidence; briefing details
- [x] Mining relevance classifier with evidence
- [x] eTenders enabled; Tharisa enabled after terms review
- [ ] Exxaro **disabled**: its disclaimer forbids commercial use and linking without written permission
- [ ] Northam, Harmony: supplier registration required. Seriti: SPA with no public endpoint.
- [ ] Sibanye, Thungela: blocked by bot protection (not bypassed). Implats: page broken at source.
- [x] DLRRD state-land leases enabled (184 adverts, 6 open on 2026-11-12). The site omits its intermediate certificate;
      a reviewed copy is trusted for dlrrd.gov.za only (see SECURITY.md).
- [ ] `BROWSER_RENDERED` and `AUTHORISED_PLATFORM` adapters (need permission or authorised accounts)

## Phase 4 — Opportunity catalogue ✅

- [x] Full-text search, filters (incl. submission method, no compulsory briefing, mining), pagination, sorting
- [x] Detail page: facts strip, requirements, documents, match, pipeline panel, source evidence
- [x] Mining and government views, organisation pages, dashboard from persisted data
- [ ] Province is populated only where the source publishes it

## Phase 5 — Document extraction ✅

- [x] Polite download of tender documents, local storage (`DocumentStorage`), byte-based type allow-list
- [x] Text extraction (Apache Tika: PDF, Office, text), searchable text, extraction status and errors
- [x] Deterministic requirement extraction with evidence sentences: CIDB, B-BBEE, preference system, CSD, tax,
      professional registration, ISO, compulsory briefing, local content
- [x] Closing date and submission method from documents, only where the listing has none (provenance `DOCUMENT`)
- [x] "Non-compulsory briefing" is not read as compulsory. When both preference systems appear, one conditional
      entry is shown. A requirement repeated across documents is listed once.
- [x] ZIP tender packs (in-memory, size-limited) and PowerPoint documents; downloads up to 60 MB
- [x] OpenDocument files (.odt, .ods, .odp), also inside ZIP tender packs
- [ ] Object storage (S3-compatible) instead of a local volume; OCR for scanned PDFs in requirement extraction
      (OCR is used for form filling, see Phase 7)

## Phase 6 — AI classification and matching ✅ (AI needs an API key)

- [x] Deterministic matching engine (weights 30/20/20/10/10/10, industry lexicon). Disqualifiers force
      `REQUIRES_MANUAL_REVIEW`. Scores never claim eligibility.
- [x] Recommended list, per-opportunity match, tenant match settings, background refresh on profile change
- [x] Race-safe match upsert (`INSERT … ON CONFLICT`), so concurrent refreshes cannot collide
- [x] AI assistant (Claude, configurable model/effort/features in Admin → AI). It uses 8 tenant-scoped tools;
      only shortlisting writes, and it needs `PIPELINE_EDIT`.
- [x] AI match explanations and classification; every AI call is audited. AI output is labelled as interpretation.
- [ ] AI features are inactive until `ANTHROPIC_API_KEY` is set (status endpoint reports `configured: false`)

## Phase 7 — Bid pipeline and notifications ✅

- [x] Tenant pipeline: stages, history, notes, tasks, go/no-go decisions, bid team
- [x] Bid documents: upload, list, download, delete per pipeline item (the V10 table had no API or UI until 2026-11-18)
- [x] **Tender form filling**: company form details (CSD, VAT, income tax, UIF, Compensation Fund, CIDB CRS,
      addresses, B-BBEE certificate) and any number of directors/members (ID numbers encrypted) are pre-filled
      into the tender's own PDF forms (SBD 1, SBD 4, SBD 6.1, MBD …). Labels are found in the text layer, or with
      Tesseract OCR on scanned pages, and table borders are detected so answers land in the right cell.
      AcroForm fields are filled directly. The user reviews and adjusts every value, then the filled PDF is
      saved as a bid document. PDFs inside ZIP packs and uploaded forms work too.
- [x] Notifications: new matches, closing soon, material changes, tasks due, source failures, focus matches
- [x] In-app notifications; email (immediate or daily digest) via SMTP, with mailpit in Compose
- [x] "My focus" areas, with a state-land-lease template fed by the DLRRD source
- [x] Focus results ranked by evidence (type/title, then description, then document text); document-only matches
      are opt-in, exclude words, and all-capitals acronyms (IT, ICT) matched exactly even when they are stop words

## Phase 8 — Analytics and production readiness ◐ mostly done

- [x] Dashboard, reports, source health, crawl monitoring, Prometheus metrics, deployment docs
- [x] Database-backed rate limiting (works across instances), retention jobs
- [x] POPIA self-service: export (`/users/me/export`) and account deletion (`/users/me/delete`)
- [x] Organisation administration for platform admins
- [ ] Load testing; PostgreSQL row-level security; privacy notice and legal text
- [x] Production compose for a single VM: Caddy (automatic HTTPS), nightly `pg_dump`, optional S3 copy (DEPLOYMENT.md)
- [x] Scheduled crawling refuses to start without a real contact in the crawler user agent

Infrastructure: host ports are configurable (`OIP_WEB_PORT`, `OIP_API_PORT`, `OIP_MAIL_UI_PORT`) so the stack can
run beside other projects.

## Known limitations and open questions

1. **AI**: set `ANTHROPIC_API_KEY` in `.env` to enable the assistant, explanations and classification. Server-side
   model fallback for refusals is on by default; it can be switched off in Admin → AI.
2. **Exxaro** needs written permission before it can be re-enabled.
3. **DLRRD**: the missing intermediate certificate is trusted for dlrrd.gov.za only. Remove it from `application.yml`
   once the department fixes its certificate chain. Province is filled only where the advert title names it.
4. The crawler user agent must carry a **real contact URL or e-mail** before scheduled crawling; the backend now
   refuses to start with a placeholder (manual crawls and local development are unaffected).
5. No PostgreSQL row-level security; tenant isolation is enforced in services and covered by tests.
6. eTenders times are interpreted as SAST (the portal shows times without a zone).
7. Privacy notice / terms text for end users is outstanding (needs legal input).

## Outstanding items

Everything still open, in one place. "Needs" says what unblocks it; items needing a decision or outside input are not
guessed at in code.

| # | Item | Needs | Next step |
|---|---|---|---|
| 1 | Privacy notice and terms of use for end users | Legal input (POPIA) | Draft with counsel; then add a page and a consent check at registration |
| 2 | Exxaro source | Written permission from Exxaro | Request permission; source stays disabled until then |
| 3 | Northam, Harmony, Seriti, Sibanye, Thungela, Implats | Supplier registration, authorised accounts or a working source page | `AUTHORISED_PLATFORM` / `BROWSER_RENDERED` adapters once access is agreed; no bot-protection bypass |
| 4 | Production crawler contact | A real domain and contact page | Set `OIP_CRAWLER_USER_AGENT` on the server (enforced at start-up) |
| 5 | AI features | `ANTHROPIC_API_KEY` | Set the key; tune in Admin → AI |
| 6 | PostgreSQL row-level security | Design decision (defence in depth vs. complexity) | Tenant isolation is enforced in services and tested today; RLS would add a second layer per tenant table |
| 7 | Load testing | Target load figures | Agree expected users/crawl volume, then run k6/Gatling against the production compose |
| 8 | Object storage for documents | Choice of provider (S3 af-south-1 vs. other) | Replace `DocumentStorage` local volume with an S3 implementation |
| 9 | OCR for requirement extraction of scanned tender PDFs | Decision on CPU time per crawl | Tesseract is now in the image (about 85 MB; used for form filling); extend `TextExtractor` to use it for scanned documents |
| 10 | DLRRD certificate workaround | DLRRD fixing its certificate chain | Remove the trusted intermediate from `application.yml` once fixed |
| 11 | Province coverage | Sources publishing province | Nothing to do in code; filled where the source or title names it |
| 12 | Word (.docx) form filling | Next iteration (agreed: PDF first) | Fill the table cell or line after each label in returnable Word forms |
| 13 | Form filling accuracy on real tenders | Feedback from real use | Detection is heuristic: label patterns in `FormField`, layout in `FieldLocator`. Collect forms that fill badly and add their wording; users can always drag, edit or add values before generating |
| 14 | Encryption key management | Production secret store | Keep `OIP_DATA_ENCRYPTION_KEY` in a secret manager with a backup; key rotation would need a re-encrypt job (not built) |

