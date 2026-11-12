# Architecture

## Overview

```mermaid
flowchart LR
  subgraph Browser
    UI[Angular 22 SPA]
  end
  subgraph Edge[nginx (frontend container)]
    STATIC[Static assets]
    PROXY[/api proxy/]
  end
  subgraph API[Spring Boot 4.1 modular monolith]
    SEC[security / identity / tenancy]
    COMP[companies]
    SRC[sources + procurement]
    CRAWL[crawling: scheduler, executor, adapters]
    ING[opportunities: ingestion, query]
    AN[analytics]
    AUD[audit]
  end
  DB[(PostgreSQL 17)]
  EXT[(Public sources: eTenders OCDS API, mining company pages)]

  UI --> STATIC
  UI --> PROXY --> SEC
  SEC --> COMP & SRC & ING & AN
  CRAWL -->|PoliteHttpClient: robots.txt, rate limits, SSRF guard| EXT
  CRAWL --> ING
  API --> DB
```

The browser talks only to its own origin; nginx proxies `/api` to the backend, so production needs no CORS.

## Backend modules (`za.co.oip`)

| Package | Responsibility |
|---|---|
| `common` | Base entity (UUID, audit columns, optimistic locking), RFC 9457 error handling, pagination envelope, reference enums |
| `configuration` | Typed `OipProperties`, JPA auditing, OpenAPI |
| `security` | JWT issue/parse, filters, auth rate limit, permissions |
| `identity` | Users, refresh-token sessions, registration/login/refresh/logout, tenant member management |
| `tenancy` | Tenants, memberships, roles |
| `subscriptions` | Default plan assignment (billing intentionally out of scope) |
| `companies` | Tenant company profile and its child claims |
| `procurement` | Procuring organisations, mining companies, supplier platforms, procurement channels, reference codes |
| `sources` | Source registry, crawl schedules, enabling rules |
| `crawling` | HTTP client, robots.txt, adapter contract and implementations, execution, scheduling, admin API |
| `opportunities` | Global opportunities, documents, change events, ingestion (dedup + change detection), catalogue queries |
| `analytics` | Dashboard figures from persisted data |
| `audit` | Append-only audit events |
| `administration` | Bootstrap of the first platform admin |

Planned modules (not yet present): `extraction`, `documents`, `classification`, `matching`, `pipeline`, `notifications`.

Rules applied throughout: controllers are thin; business rules live in services; DTOs (records) at the API
boundary; every tenant-scoped query takes the tenant id from the authenticated principal, never from the client.

## Key design decisions

| Decision | Reason |
|---|---|
| Modular monolith | One deployable until load justifies extracting the crawler/extraction workers. Module boundaries are package-level and crawling talks to ingestion through a service interface. |
| **No Spring Batch** | Spring Batch 6 (Boot 4) needs separate JDBC job-repository wiring and its schema duplicates `crawl_jobs`/`crawl_job_runs`. Crawls are short, per-source and already need custom outcome classification; a bounded executor + DB-tracked jobs/runs + DB leases is simpler and fully observable. |
| **Redis deferred** | Nothing needs it yet. Scheduling safety across instances uses a DB lease (`crawl_schedules.lease_until`). Redis becomes useful for the auth rate limiter and caching when the API is scaled out. |
| Opportunities are global | Stored once (`opportunities`), tenant relationships will live in `tenant_opportunities` (Phase 7). |
| Catalogue uses SQL, not JPA criteria | Needs PostgreSQL full-text search (`tsvector` + `websearch_to_tsquery`), ranking and sector joins. All inputs are bound parameters. |
| Jackson 3 | Boot 4 default. Optional request booleans/numbers are wrapper types because Jackson 3 rejects nulls for primitives. |
| Times | Stored as UTC `timestamptz`; South African local dates (`closing_date`) stored separately because many sources publish only a date. Displayed in `Africa/Johannesburg`. |

## Frontend

Angular 22 standalone components, signals, zoneless change detection, Angular Material 3 theme, lazy-loaded
routes. `core/` holds API types, the `ApiService`, the signal-based `AuthService`, the refresh-on-401
interceptor and route guards. Features: dashboard, catalogue (all/mining/government), opportunity detail with
evidence, organisations, company profile, team, admin sources, crawl monitoring. Navigation entries for
unbuilt phases are shown disabled and labelled "Planned".
