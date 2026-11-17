#!/bin/sh
# Copies the newest nightly database dump and the document store off the server to S3.
# Run from the repository directory on the server, e.g. daily from cron:
#   30 3 * * * cd /opt/oip && OIP_BACKUP_BUCKET=s3://my-oip-backups deploy/backup-to-s3.sh >> /var/log/oip-backup.log 2>&1
# Needs the AWS CLI with credentials limited to s3:PutObject on that bucket. Use a bucket lifecycle rule for retention.
set -eu
: "${OIP_BACKUP_BUCKET:?Set OIP_BACKUP_BUCKET, e.g. s3://my-oip-backups}"

latest=$(docker compose -f docker-compose.prod.yml exec -T backup sh -c 'ls -1t /backups/oip-*.sql.gz | head -n 1')
[ -n "$latest" ] || { echo "no database dump found" >&2; exit 1; }
docker compose -f docker-compose.prod.yml exec -T backup cat "$latest" \
  | aws s3 cp - "$OIP_BACKUP_BUCKET/db/$(basename "$latest")"

# Downloaded tender documents can be fetched again from the sources; uploaded company documents cannot.
docker compose -f docker-compose.prod.yml exec -T backend tar -C /data -czf - storage \
  | aws s3 cp - "$OIP_BACKUP_BUCKET/storage/storage-$(date -u +%Y%m%d).tar.gz"
echo "$(date -u +%FT%TZ) uploaded $(basename "$latest") and document store"
