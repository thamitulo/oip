# Opportunity Intelligence Platform (OIP)

Discovers, collects and monitors procurement opportunities from South African government and mining-sector
sources, keeps traceable evidence for every record, and (in later phases) matches them to subscribing
businesses and tracks their bids.

> **Status:** all eight MVP phases are implemented and tested: foundation, source registry, crawler, catalogue,
> document extraction, matching and AI, bid pipeline and notifications, reporting and hardening. Open items and
> limitations are listed in [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md).

## What it does

- **Discovers real opportunities.** All ~2,000 currently published South African government tenders from the
  eTenders portal feed (with e-submission flag, delivery address, province, compulsory briefing and contacts), and
  mining-company RFQs from Tharisa. Public tenders with a mining subject are flagged for the mining view.
- **Shows how to submit.** Every opportunity carries a submission method (electronic / physical / not stated) with
  the channel or delivery address and where that information came from.
- **Reads the documents.** Tender documents are downloaded, stored and text-extracted; CIDB grading, B-BBEE,
  CSD, tax, professional registrations, ISO certifications, compulsory briefings, submission method and missing
  closing dates are extracted with the sentence they came from.
- **Matches to your company.** An explainable 0–100 match per tenant with matched items, unknowns and
  disqualifiers (missing mandatory requirements); configurable weights.
- **AI assistant (optional, configurable).** A Claude-powered assistant that searches opportunities, reads
  documents, explains matches and can shortlist on request — always grounded in platform data, tenant-scoped,
  audited and rate-limited. Plus AI fit assessment and classification, labelled as AI.
- **Runs your bid pipeline.** Shortlist, stages, assignment, notes, tasks with reminders, go/no-go decisions and
  history — per tenant, never altering the shared opportunity.
- **Notifies.** New matches, closing reminders, changes at the source, assignments and due tasks — in-app and by
  e-mail (immediate or daily digest), with deduplication.
- **Operates safely.** Source registry with verified access/terms status, polite crawler (robots.txt, rate
  limits, SSRF protection), evidence for every record, POPIA export/delete, retention jobs, audit trail.

## Repository layout

```
backend/    Spring Boot 4.1 (Java 21) modular monolith, Flyway migrations, tests
frontend/   Angular 22 + Angular Material (standalone components, signals, zoneless)
docs/       Architecture, database, API, crawler, sources, security, deployment, status
docker-compose.yml, .env.example
```

## Quick start (Docker)

Requirements: Docker with Compose v2.

```bash
cp .env.example .env
# Edit .env: set OIP_DB_PASSWORD, OIP_JWT_SECRET (openssl rand -base64 48),
# OIP_BOOTSTRAP_ADMIN_EMAIL and OIP_BOOTSTRAP_ADMIN_PASSWORD (12+ characters).
docker compose up --build
```

- App: http://localhost:4200 (sign in with the bootstrap admin, or register a business account)
- Local e-mail (Mailpit): http://localhost:8025
- AI: optional — set `ANTHROPIC_API_KEY` in `.env` to enable the assistant, then tune model/effort/limits under Administration → AI settings
- API docs (Swagger UI): http://localhost:4200/swagger-ui.html, OpenAPI JSON at `/v3/api-docs`
- Health: http://localhost:8080/actuator/health

Scheduled crawling is **off** locally (`OIP_CRAWLER_SCHEDULER_ENABLED=false`). As the platform admin, open
**Source management → eTenders OCDS API → Crawl now** to fetch real tenders.

## Local development

Requirements: JDK 21+ (built with `--release 21`), Node 24+, Docker (for PostgreSQL and Testcontainers).

```bash
docker compose up -d postgres                     # database only
cd backend
set -a; . ../.env; set +a                         # bash; values in .env are quoted where needed
export OIP_DB_URL=jdbc:postgresql://localhost:5432/oip OIP_DB_USERNAME=oip
./mvnw spring-boot:run                            # API on :8080

cd ../frontend
npm install
npm start                                         # http://localhost:4200, proxies /api to :8080
```

### Commands

| Purpose | Command |
|---|---|
| Backend tests (unit + Testcontainers integration) | `cd backend && ./mvnw test` |
| Backend package | `cd backend && ./mvnw package` |
| Frontend tests (Vitest) | `cd frontend && npx ng test --watch=false` |
| Frontend production build | `cd frontend && npx ng build` |
| Full stack | `docker compose up --build` |

Integration tests start PostgreSQL 17 with Testcontainers, so Docker must be running.

## Configuration

All configuration is via environment variables (see [.env.example](.env.example)); no secret has a usable default.

| Variable | Purpose | Default |
|---|---|---|
| `OIP_DB_URL`, `OIP_DB_USERNAME`, `OIP_DB_PASSWORD` | PostgreSQL connection | `jdbc:postgresql://localhost:5432/oip`, `oip`, — |
| `OIP_JWT_SECRET` | HS256 signing key, ≥ 32 chars | **required** |
| `OIP_ACCESS_TOKEN_TTL` / `OIP_REFRESH_TOKEN_TTL` | Token lifetimes | `PT15M` / `P14D` |
| `OIP_CORS_ALLOWED_ORIGINS` | Allowed browser origins when not same-origin | `http://localhost:4200` |
| `OIP_AUTH_RATE_LIMIT_PER_MINUTE` | Per-IP limit on `/api/v1/auth/*` | `30` |
| `OIP_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` / `_TENANT` | First platform admin (created once) | — |
| `OIP_CRAWLER_SCHEDULER_ENABLED` | Run scheduled crawls in this instance | `true` (compose: `false`) |
| `OIP_CRAWLER_USER_AGENT` | Honest crawler identity with contact URL | `OIP-Crawler/0.1 (+https://example.invalid/crawler)` |
| `OIP_CRAWLER_MAX_CONCURRENT`, `OIP_CRAWLER_REQUEST_TIMEOUT`, `OIP_CRAWLER_MAX_RESPONSE_BYTES` | Crawler limits | `2`, `PT30S`, 15 MB |
| `OIP_LOG_FORMAT` | `ecs`/`logstash` for JSON logs | plain |
| `ANTHROPIC_API_KEY` | Enables AI features (assistant, fit assessment, classification) | — (AI off) |
| `OIP_MAIL_ENABLED`, `OIP_MAIL_HOST/PORT/USERNAME/PASSWORD`, `OIP_MAIL_FROM` | E-mail notifications | Compose: Mailpit |
| `OIP_APP_URL` | Base URL used in e-mail links | `http://localhost:4200` |
| `OIP_STORAGE_DIR` | Stored documents | `./data/storage` (Compose: volume) |
| `OIP_SECURE_COOKIES` | Secure flag on the refresh cookie (keep `true` behind HTTPS) | `true` (Compose: `false`) |
| `OIP_DOCUMENTS_PROCESSING_ENABLED`, `OIP_DOCUMENTS_BATCH_SIZE`, `OIP_DOCUMENTS_RETENTION_DAYS` | Document pipeline | `true`, `10`, `90` |

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Database design & ERD](docs/DATABASE_DESIGN.md)
- [API specification](docs/API_SPECIFICATION.md)
- [Crawler design](docs/CRAWLER_DESIGN.md)
- [Source register](docs/SOURCE_REGISTER.md) — what was verified about each source, and how
- [AI and matching](docs/AI_MATCHING.md)
- [Security & POPIA](docs/SECURITY.md)
- [Deployment](docs/DEPLOYMENT.md)
- [Implementation status & MVP checklist](docs/IMPLEMENTATION_STATUS.md)

## Data principles

- No fabricated opportunities: opportunities only come from crawling sources. Tests use clearly labelled synthetic fixtures.
- Every opportunity keeps its original source URL, the exact URL it was read from, discovery and check timestamps, and a content hash.
- Values the source does not publish (value, closing time, requirements) stay empty; inferred values (e.g. status from closing date) are flagged as inferred.
- No bypassing of logins, paywalls, CAPTCHAs, bot protection or robots.txt.
