# API specification

Base path `/api/v1`. Interactive documentation: `/swagger-ui.html`; machine-readable OpenAPI: `/v3/api-docs`.

- **Auth:** `Authorization: Bearer <accessToken>` (15-minute JWT). Refresh with the opaque refresh token; each
  refresh rotates it, and re-use of a rotated token revokes all of the user's sessions.
- **Errors:** RFC 9457 `application/problem+json` with `title`, `status`, `detail`, and `errors[]` (`field`,
  `message`) for validation failures. Codes: 400 validation, 401 unauthenticated, 403 forbidden, 404 not found
  (also used for other tenants' resources), 409 conflict, 422 business rule, 429 rate limited.
- **Pagination:** `?page=0&size=20` → `{ content, page, size, totalElements, totalPages }`.
- **Tenancy:** the tenant is always taken from the token.

## Authentication and users

| Method | Path | Notes |
|---|---|---|
| POST | `/auth/register` | `{email, password(≥10), fullName, companyName, acceptTerms:true, marketingConsent?}` → creates tenant, owner, profile, subscription; returns tokens |
| POST | `/auth/login` | `{email, password, tenantId?}` |
| POST | `/auth/refresh` | `{refreshToken}` |
| POST | `/auth/logout` | `{refreshToken}` → 204 |
| GET | `/users/me` | Current user, tenant, role, permissions, memberships |
| GET | `/users` | Members of the current tenant |
| POST | `/auth/forgot-password` | `{email}` → always 202 (does not reveal whether the account exists); e-mails a 1-hour reset link |
| POST | `/auth/token-info` | `{token}` → `{purpose, fullName, tenantName}` for an e-mailed link, without using it |
| POST | `/auth/set-password` | `{token, password}` → 204. Reset or invitation link; single use; signs out all sessions |
| POST | `/auth/verify-email` | `{token}` → 204. Confirms the address |
| POST | `/auth/verify-email/resend` | Signed in. Sends a new verification link |
| POST | `/users` | `USERS_MANAGE`. `{email, fullName, role}` → `{member, invitationEmailed, invitationUrl}`. New people are invited by e-mail to set their own password (`invitationUrl` only when e-mail is disabled). `initialPassword` is optional for API use. Cannot grant above own role |
| POST | `/users/{id}/resend-invitation` | `USERS_MANAGE`. New invitation link for a member who has not accepted |
| PUT | `/users/{id}` | `USERS_MANAGE`. `{role?, status?}`; last owner cannot be demoted/disabled |

## Tenant and company profile

| Method | Path | Notes |
|---|---|---|
| GET/PUT | `/tenants/current` | PUT needs `TENANT_MANAGE`: `{name, timezone}` |
| GET/PUT | `/companies/profile` | PUT needs `COMPANY_PROFILE_EDIT`; full replacement. `VERIFIED` cannot be self-assigned |
| GET | `/companies/capabilities` | Declared capabilities |

## Opportunities (`OPPORTUNITIES_VIEW`)

| Method | Path | Notes |
|---|---|---|
| GET | `/opportunities` | Filters: `q, organisationId, sector, eventType, procurementCategory, province, locality, status, activeOnly(default true), closingFrom, closingTo, publishedFrom, publishedTo, sourceId, includeDuplicates, sort(CLOSING_SOONEST/NEWEST/RELEVANCE/PUBLISHED)` |
| GET | `/opportunities/search?q=` | Full-text, relevance-ranked |
| GET | `/opportunities/closing-soon?days=7` | Active, closing within N days |
| GET | `/opportunities/{id}` | Detail, documents, categories with provenance, **evidence** |
| GET | `/opportunities/{id}/documents` | Documents, including ones removed from the source |
| GET | `/opportunities/{id}/history` | Detected changes with old/new values and materiality |

Summary cards include `urgency` (`CLOSING_TODAY`, `URGENT` ≤3 days, `SOON` ≤7, `OPEN`, `CLOSED`, `UNKNOWN`),
`sourceVerified`, `statusInferred` and `closingTimeSpecified`.

## Organisations and reference data

| Method | Path |
|---|---|
| GET | `/organisations?sector=&q=` |
| GET | `/organisations/{id}` (channels, supplier platforms, monitored sources, commodities) |
| GET | `/supplier-platforms` |
| GET | `/reference/{EVENT_TYPE\|OPPORTUNITY_STATUS\|PROCUREMENT_CATEGORY\|INDUSTRY}` |

## Analytics

| GET | `/analytics/dashboard` | Active, new today, closing in 7 days, mining/government, source health, breakdowns, recent source updates |
|---|---|---|

## Administration (platform administrators only)

| Method | Path | Notes |
|---|---|---|
| GET/POST | `/admin/sources` | Search/create. Enabling is rejected (422) with reasons if access is not permitted |
| GET/PUT | `/admin/sources/{id}` | Includes `crawlBlockers` |
| POST | `/admin/sources/{id}/crawl` | 202 `{jobId}`; runs asynchronously |
| POST | `/admin/sources/{id}/test` | Dry run: outcome, preview of parsed records, item errors, log; stores nothing |
| GET | `/admin/crawls?sourceId=&status=` | Run history |
| GET | `/admin/crawls/{runId}` | Errors, fetched evidence (URL, status, hash, excerpt), execution log |
| GET | `/admin/source-errors?sourceId=` | Recent errors |
| GET | `/admin/source-health` | Per source: last outcome, freshness, failure streak, opportunity count, `HEALTHY/STALE/FAILING/NEVER_RUN/NOT_ACTIVE` |

## Documents and requirements

| Method | Path | Notes |
|---|---|---|
| GET | `/opportunities/{id}/requirements` | Requirements with `provenance` (SOURCE/DOCUMENT/AI) and quoted evidence |
| POST | `/opportunities/{id}/documents/process` | 202; download and extract this opportunity's documents now |
| GET | `/documents/{storedId}/original` | Stored original (attachment) |
| GET | `/documents/{storedId}/text` | Extracted text |
| GET/POST | `/companies/documents` | List / upload (multipart `file`, `documentType`, `description`; 25 MB; `COMPANY_PROFILE_EDIT`) |
| GET/DELETE | `/companies/documents/{id}` | Download / delete (tenant-scoped) |

Opportunity responses include `submissionMethod` (`ELECTRONIC`, `PHYSICAL`, `ELECTRONIC_OR_PHYSICAL`,
`NOT_SPECIFIED`); detail includes `submission {method, channel, address, twoEnvelope, provenance, evidence}`,
`briefingAt`, `briefingVenue`, `specialConditions`, `miningEvidence`. List filters add `submissionMethod` and
`compulsoryBriefing`.

## Matching and AI

| Method | Path | Notes |
|---|---|---|
| GET | `/opportunities/{id}/match` | Score, label, dimension scores, matched, missing, disqualifiers, AI explanation |
| GET | `/opportunities/recommended` (alias `/opportunities/matches`) | `minScore`, paging |
| GET/PUT | `/companies/match-settings` | Weights (sum 100) and thresholds; PUT needs `TENANT_MANAGE` |
| GET | `/ai/status` | Which AI features are available |
| POST | `/ai/assistant/messages` | `{conversationId?, message}` → reply with `toolsUsed` |
| GET/DELETE | `/ai/assistant/conversations[/{id}]` | The caller's conversations |
| POST | `/opportunities/{id}/match/explain` | AI fit assessment (stored with the match) |
| POST | `/opportunities/{id}/classify` | AI classification (provenance AI) |
| GET/PUT | `/admin/ai/settings`, GET `/admin/ai/usage` | Platform admin |

## Bid pipeline (`/tenant`)

| Method | Path | Notes |
|---|---|---|
| GET | `/tenant/pipeline` | Board |
| GET | `/tenant/opportunities/{id}` | Item, notes, tasks, decisions, history |
| POST | `/tenant/opportunities/{id}/shortlist` | Adds as SHORTLISTED (idempotent) |
| PUT | `/tenant/opportunities/{id}/status` | `{stage, comment}` |
| POST | `/tenant/opportunities/{id}/assign` | `{userId}` (member of the tenant, or null) |
| PUT | `/tenant/opportunities/{id}/details` | Priority, bid value, outcome notes |
| POST | `/tenant/opportunities/{id}/notes` / `tasks` / `decisions` | Note; task `{title, dueAt, assigneeId, remindAt}`; decision `{GO|NO_GO, rationale}` |
| GET / PUT | `/tenant/tasks?mine=` / `/tenant/tasks/{id}` | Open tasks / status |

Changes need `PIPELINE_EDIT`. Pipeline data is tenant-specific and never changes the global opportunity.

## Notifications, reports, privacy, administration

| Method | Path |
|---|---|
| GET | `/notifications?unreadOnly=`, `/notifications/unread-count` |
| POST | `/notifications/{id}/read`, `/notifications/read-all` |
| GET/PUT | `/notifications/preferences` |
| GET | `/analytics/pipeline`, `/analytics/opportunities?weeks=`, `/analytics/categories`, `/analytics/organisations` |
| GET / POST | `/users/me/export` / `/users/me/delete` `{password}` |
| POST/PUT | `/admin/organisations[/{id}]`, `/admin/organisations/{id}/channels[/{channelId}]` (DELETE channel) |

Authentication: `/auth/refresh` and `/auth/logout` accept the refresh token from the `oip_refresh` httpOnly cookie
(web app) or the body (API clients); login/register/refresh set the cookie.
