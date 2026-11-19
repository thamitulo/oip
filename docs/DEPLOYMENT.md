# Deployment

## Containers

| Image | Build | Port | Notes |
|---|---|---|---|
| backend | `backend/Dockerfile` (Temurin 21, non-root) | 8080 | Readiness `/actuator/health/readiness`, liveness `/actuator/health/liveness` |
| frontend | `frontend/Dockerfile` (nginx) | 80 | Serves the SPA, proxies `/api`, `/v3/api-docs`, `/swagger-ui` to `backend:8080`, sets security headers |
| postgres | `postgres:17-alpine` | 5432 | Use a managed service in production |

The backend image includes Tesseract OCR (English) for scanned tender forms, which adds about 85 MB. Without it
(e.g. running the jar locally on Windows) digital PDFs are still filled and scanned pages are reported as
unreadable. Settings: `OIP_OCR_ENABLED`, `OIP_OCR_COMMAND`, `OIP_OCR_LANGUAGE`, `OIP_OCR_MAX_PAGES` (30 scanned
pages per form).

`OIP_DATA_ENCRYPTION_KEY` (`openssl rand -base64 32`) encrypts directors' identity numbers. It is required by
`docker-compose.prod.yml` and optional locally. Store a copy outside the server; restoring a database backup
without the same key leaves identity numbers unreadable.

Local: `docker compose up --build` (see README). Compose binds ports to `127.0.0.1` only. Host ports are
configurable when another project already uses them: `OIP_WEB_PORT` (4200), `OIP_API_PORT` (8080),
`OIP_MAIL_UI_PORT` (8025), e.g. `OIP_WEB_PORT=4300 OIP_API_PORT=8180 docker compose up -d`. Set `OIP_APP_URL` to
the address people use, because it is used in e-mailed links.

## Production guidance (any container platform)

1. **Database:** managed PostgreSQL 17 (e.g. AWS RDS/Aurora, Azure Database for PostgreSQL, Google Cloud SQL)
   with automated backups and point-in-time recovery. Flyway migrates on startup; run one instance first when
   deploying a new migration, or run migrations as a release job.
2. **Secrets:** on AWS they come from AWS Secrets Manager (`SPRING_PROFILES_ACTIVE=aws`, see [AWS-SECRETS.md](AWS-SECRETS.md)): `OIP_DB_PASSWORD`, `OIP_JWT_SECRET`, `OIP_DATA_ENCRYPTION_KEY`, `OIP_BOOTSTRAP_ADMIN_*`, ...
   Remove the bootstrap admin variables after the first start.
3. **TLS & edge:** terminate TLS at a load balancer in front of the frontend container; set
   `OIP_FORWARD_HEADERS_STRATEGY=framework` on the backend if it receives forwarded headers.
4. **Scaling:** the API is stateless. Crawl leases make multiple instances safe; you can also run crawling in a
   dedicated instance (`OIP_CRAWLER_SCHEDULER_ENABLED=true` there, `false` on API instances). The auth rate
   limiter is stored in PostgreSQL, so it applies across instances. Background jobs (documents, matching,
   notifications) are idempotent; to run them on one instance only, set `OIP_DOCUMENTS_PROCESSING_ENABLED=false`
   and `oip.matching.background-enabled` / `oip.notifications.jobs-enabled` to false on the others.
5. **Crawler identity:** set `OIP_CRAWLER_USER_AGENT` with a real contact URL/email page for source operators.
   With scheduled crawling on, the backend refuses to start while it has no contact or still uses a placeholder
   from the example files (`example.invalid`, `your-domain`, `example.co.za`, …).
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

## Recommended: one small VM (cheapest that fits)

The platform needs an always-on machine: the crawler, document extraction and notification jobs run on schedules
inside the backend, so scale-to-zero platforms (Lambda, App Runner/Cloud Run with min 0) would stop them.
Memory is the constraint: backend ~1.5 GB (document extraction of large tender packs peaks here), PostgreSQL
~0.5 GB, nginx/Caddy ~0.1 GB. **Use 4 GB RAM**; 2 GB works only with AI and document processing kept light.

| Option | Approx. cost / month | Notes |
|---|---|---|
| **AWS Lightsail 4 GB / 2 vCPU / 80 GB** (recommended) | ~US$24 + ~US$4 snapshots | Fixed price, 4 TB transfer included, static IP free while attached. Everything below. |
| AWS EC2 t3.medium in af-south-1 (Cape Town) + EBS | ~US$40–45 | Choose if data must stay in South Africa (POPIA policy) or you need VPC/IAM integration. Same compose file. |
| AWS ECS Fargate + RDS + ALB | ~US$90–130 | Managed and multi-AZ; worth it once uptime matters more than cost. ALB alone is ~US$18. |

Check the current price in the AWS console before launching; regional prices differ.

### Lightsail step by step

1. **Create the instance:** Lightsail → Create instance → Linux/Ubuntu 24.04 LTS, 4 GB plan. Attach a **static IP**.
   Networking: allow TCP 22 (restrict to your IP), 80, 443 and UDP 443. Enable **automatic snapshots** (daily).
2. **DNS:** create an A record (e.g. `oip.example.co.za`) pointing at the static IP. Caddy needs it to issue the certificate.
3. **Install Docker:**
   ```bash
   curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker $USER && newgrp docker
   ```
4. **Deploy:**
   ```bash
   sudo mkdir -p /opt/oip && sudo chown $USER /opt/oip && git clone <repo> /opt/oip && cd /opt/oip
   cp .env.production.example .env && chmod 600 .env && nano .env      # fill in every value
   docker compose -f docker-compose.prod.yml up -d --build
   docker compose -f docker-compose.prod.yml logs -f backend caddy      # wait for "Started" and the certificate
   ```
   Open `https://<OIP_DOMAIN>` and sign in with the bootstrap admin. Then remove `OIP_BOOTSTRAP_ADMIN_*` from
   `.env` and run `docker compose -f docker-compose.prod.yml up -d`.
5. **E-mail:** use an SMTP service (Amazon SES SMTP credentials, Brevo or Mailgun). Verify your sending domain
   (SPF/DKIM) or invitations and password-reset mails will land in spam.
6. **Backups:** the `backup` service writes a nightly `pg_dump` to the `oip-backups` volume (kept
   `OIP_BACKUP_KEEP_DAYS`). Lightsail snapshots copy the whole disk. For an off-machine copy, create an S3 bucket
   and run `deploy/backup-to-s3.sh` from cron (instructions in the script).
   Restore: `gunzip -c oip-<date>.sql.gz | docker compose -f docker-compose.prod.yml exec -T postgres psql -U oip -d oip`
   into an empty database.
7. **Updates:**
   ```bash
   cd /opt/oip && git pull && docker compose -f docker-compose.prod.yml up -d --build && docker image prune -f
   ```
   Flyway applies new migrations on start. Take a snapshot first when a release contains a migration.

What the production compose file does differently from the local one: only Caddy is published (80/443, automatic
HTTPS, `/actuator` blocked); secure cookies on; forwarded headers trusted so the auth rate limiter sees each
user's IP rather than the proxy's; scheduled crawling on; Mailpit removed; restart policies, memory limits and
log rotation set; nightly database dumps.

## Backups and retention

Back up PostgreSQL daily (opportunities, evidence and tenant data are all in the database); the production
compose file does this (see above). Expired sessions, old notifications and audit events are purged nightly by
`RetentionJobs` using the periods in `system_settings`; downloaded document originals after
`OIP_DOCUMENTS_RETENTION_DAYS`.
