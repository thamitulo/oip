# Deployment

## Containers

| Image | Build | Port | Notes |
|---|---|---|---|
| backend | `backend/Dockerfile` (Temurin 21, non-root) | 8080 | Readiness `/actuator/health/readiness`, liveness `/actuator/health/liveness` |
| frontend | `frontend/Dockerfile` (nginx) | 80 | Serves the SPA, proxies `/api`, `/v3/api-docs`, `/swagger-ui` to `backend:8080`, sets security headers |
| postgres | `postgres:17-alpine` | 5432 | Use a managed service in production |

Local: `docker compose up --build` (see README). Compose binds ports to `127.0.0.1` only.

## Production guidance (any container platform)

1. **Database:** managed PostgreSQL 17 (e.g. AWS RDS/Aurora, Azure Database for PostgreSQL, Google Cloud SQL)
   with automated backups and point-in-time recovery. Flyway migrates on startup; run one instance first when
   deploying a new migration, or run migrations as a release job.
2. **Secrets:** inject `OIP_DB_PASSWORD`, `OIP_JWT_SECRET`, `OIP_BOOTSTRAP_ADMIN_*` from a secret manager.
   Remove the bootstrap admin variables after the first start.
3. **TLS & edge:** terminate TLS at a load balancer in front of the frontend container; set
   `OIP_FORWARD_HEADERS_STRATEGY=framework` on the backend if it receives forwarded headers.
4. **Scaling:** the API is stateless. Crawl leases make multiple instances safe; you can also run crawling in a
   dedicated instance (`OIP_CRAWLER_SCHEDULER_ENABLED=true` there, `false` on API instances). The auth rate
   limiter is stored in PostgreSQL, so it applies across instances. Background jobs (documents, matching,
   notifications) are idempotent; to run them on one instance only, set `OIP_DOCUMENTS_PROCESSING_ENABLED=false`
   and `oip.matching.background-enabled` / `oip.notifications.jobs-enabled` to false on the others.
5. **Crawler identity:** set `OIP_CRAWLER_USER_AGENT` with a real contact URL/email page for source operators.
   Outbound traffic should leave from a stable IP so operators can identify or allow-list it.
6. **AI (optional):** set `ANTHROPIC_API_KEY`; choose model, effort and daily limits under Administration → AI settings.
7. **E-mail:** set `OIP_MAIL_ENABLED=true`, `OIP_MAIL_HOST/PORT/USERNAME/PASSWORD`, `OIP_MAIL_FROM` and `OIP_APP_URL`
   (links in e-mails). Compose uses the bundled Mailpit (http://localhost:8025).
8. **Documents:** mount persistent storage at `OIP_STORAGE_DIR` (Compose: volume `oip-storage`). Back it up with the database.
9. **Cookies:** keep `OIP_SECURE_COOKIES=true` (default) behind HTTPS.
10. **Observability:** scrape `/actuator/prometheus` (platform-admin token required, or expose the management
   port on an internal network only). Useful metrics: `oip_crawl_runs_total{source,outcome}`,
   `oip_crawl_opportunities_discovered_total`, `http_server_requests_seconds`, HikariCP and JVM metrics. Set
   `OIP_LOG_FORMAT=ecs` for JSON logs. Suggested alerts: a source's last outcome is failing for 2 runs;
   no successful eTenders crawl in 36 h; 5xx rate > 1 %.
11. **Resources:** start with 1 vCPU / 1 GB for the backend (`-XX:MaxRAMPercentage=75`), 0.25 vCPU / 128 MB for nginx.

## Example: single VM

```bash
git clone <repo> oip && cd oip
cp .env.example .env   # fill in secrets; set OIP_CRAWLER_SCHEDULER_ENABLED=true
docker compose up -d --build
# put Caddy/nginx with TLS in front of 127.0.0.1:4200
```

## Backups and retention

Back up PostgreSQL daily (opportunities, evidence and tenant data are all in the database). Retention policies
for `source_observations` excerpts, `audit_events` and expired `user_sessions` are not automated yet.
