# Security and compliance

## Authentication

- Passwords: Spring Security delegating encoder (bcrypt). Minimum 10 characters. Login errors do not reveal
  whether the email exists, and a dummy hash keeps timing similar.
- Access tokens: HS256 JWT, 15 minutes, signed with `OIP_JWT_SECRET` (≥ 32 chars, required, no default).
  Claims: user, tenant, role, platform-admin flag. Isolated in `JwtService` so an OIDC provider can replace it.
- Refresh tokens: 256-bit random, stored only as SHA-256 hashes in `user_sessions`, single use with rotation;
  re-use of a rotated token revokes all of the user's sessions. Logout revokes the token.
- Rate limit: 30 requests/minute per IP on `/api/v1/auth/*`, counted in PostgreSQL so it holds across instances.

- The web app never stores tokens: the refresh token is set as an `httpOnly; Secure; SameSite=Strict` cookie scoped
  to `/api/v1/auth`, and the access token is kept in memory. On load the app restores the session with
  `/auth/refresh` (cookie). API clients may still send the refresh token in the body. `OIP_SECURE_COOKIES=false` is
  only for plain-HTTP localhost.

## Authorisation and tenant isolation

- Roles per tenant (`TENANT_OWNER` … `READ_ONLY_USER`) map to permissions (`TENANT_MANAGE`, `USERS_MANAGE`,
  `COMPANY_PROFILE_EDIT`, `PIPELINE_EDIT`, `OPPORTUNITIES_VIEW`); `PLATFORM_ADMIN` is a user flag. A test asserts
  the Java mapping equals the seeded `role_permissions`.
- `@PreAuthorize` on mutating endpoints; `/api/v1/admin/**` and non-health actuator endpoints require
  `PLATFORM_ADMIN`.
- Tenant id always comes from the token. Services query by `(tenant_id, id)`; another tenant's resource returns
  404. Users cannot grant roles above their own, modify higher-ranked members, or remove the last owner.
- Tested: `TenantIsolationIT`, `CompanyProfileIT`, `AuthFlowIT`.
- PostgreSQL row-level security is **not** enabled yet; it should be added together with per-request
  `SET app.tenant_id` handling and tests once tenant pipeline data exists.

## Input, output and transport

- Bean Validation on all request DTOs; RFC 9457 errors without stack traces or internal messages.
- JPA parameter binding / named SQL parameters everywhere (no string-concatenated user input).
- CSP, `X-Frame-Options: DENY`, `nosniff`, referrer policy (nginx and Spring Security).
- CORS limited to configured origins (production runs same-origin behind nginx).
- Run behind TLS in production (see DEPLOYMENT.md).

## Crawler safety

Truthful user agent, robots.txt, rate limits, timeouts, response-size limit, SSRF protection on every
redirect, XML DTDs disabled for feeds. No circumvention of logins, paywalls, CAPTCHAs or bot protection.
Sources record terms-of-use review status and licence notes; a DB constraint prevents enabling sources whose
access is not permitted.

TLS validation is never relaxed. A few sites omit their issuing (intermediate) CA from the handshake; browsers fetch
it automatically, the crawler does not. For those, `oip.crawler.trust.additional-intermediates` lists a reviewed copy of
the public intermediate, bound to exact host names and pinned by SHA-256 fingerprint (checked at start-up). It is used only
for path building for those hosts: the chain must still reach a JDK-trusted root, and the hostname, validity and key usage
are still checked. Current entry: Sectigo Public Server Authentication CA OV R36 for `www.dlrrd.gov.za`/`dlrrd.gov.za`
(downloaded from the site's AIA URL, verified to chain to Sectigo Root R46 / USERTrust RSA). Remove it when the site
fixes its chain.

## POPIA

- Minimal personal information: name, email, optional phone/contact details, optional voluntary ownership data.
- Consent: acceptance of terms recorded with timestamp (`terms_accepted_at`); marketing consent recorded
  separately and optional.
- Access restricted per tenant and role; audit trail of security and data changes (`audit_events`).
- Procurement contact details collected from public sources stay attached to their source evidence.
- Self-service: `GET /users/me/export` (all personal data as JSON) and `POST /users/me/delete` (password required;
  anonymises the user and deletes sessions, notifications, preferences and assistant conversations; blocked while
  the user is the sole owner of a business with other members).
- Retention (nightly, configurable in `system_settings`): sessions 30 days after expiry, notifications 180 days,
  observation excerpts 180 days, audit events 730 days, stored originals 90 days after closing.
- Still to do: a published privacy notice and terms of use (legal text), and an operator process for requests
  received outside the app.

## AI

- Optional; requires `ANTHROPIC_API_KEY` in the environment (never stored in the database or sent to the browser).
- Assistant tools execute as the signed-in user with tenant scoping and permission checks; the model cannot choose
  a tenant. Only one write tool (shortlist), used on explicit request.
- Assistant output is rendered from Markdown with HTML escaped and only relative or http(s) links allowed.
- Every AI call is audited; daily limits per tenant/platform; outputs labelled AI.
- Company and opportunity data sent to the provider is limited to what the feature needs.

## Documents

Uploads (25 MB max) are type-checked from their bytes against an allow-list; storage keys are platform-generated
and validated against path traversal; downloads are served as attachments with `nosniff`; company documents are
tenant-scoped.

## Secrets

Only environment variables (`.env` is git-ignored). Use a secret manager in production.
