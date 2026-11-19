# Secrets on AWS

OIP runs on AWS. There it reads its secrets from **AWS Secrets Manager** at startup. They are not kept in env files,
images or task definitions. Local development is unchanged (`.env` + `docker-compose*.yml`); Secrets Manager is off unless the `aws`
profile is active.

## How it works

- `SPRING_PROFILES_ACTIVE=aws` activates `backend/src/main/resources/application-aws.yml`, which imports
  `aws-secretsmanager:${OIP_AWS_SECRET_ID:oip/prod}` through Spring Cloud AWS.
- The secret is **one JSON object**. Its keys use **the same names as the environment variables** in
  `application.yml`, so every `${...}` placeholder takes its value from the secret. A real environment variable
  overrides a value in the secret.
- **The app fails fast.** If the secret can't be read (wrong name, no permission, no network path), the app stops at
  startup instead of running without its secrets. `AwsSecretsProfileTest` covers this.
- Region and credentials come from the standard AWS chain (ECS task role, EC2 instance profile). No access keys
  go in config.

One secret per environment: `oip/prod`, `oip/staging` (set `OIP_AWS_SECRET_ID`), ...

## Keys

| Key | Required | Notes |
|---|---|---|
| `OIP_DB_URL` | yes | `jdbc:postgresql://<rds-endpoint>:5432/oip` |
| `OIP_DB_USERNAME` | yes |  |
| `OIP_DB_PASSWORD` | yes | Generated. |
| `OIP_JWT_SECRET` | yes | `openssl rand -base64 48` |
| `OIP_DATA_ENCRYPTION_KEY` | yes | `openssl rand -base64 32`. **Back it up**: losing it makes encrypted personal data unreadable. |
| `OIP_BOOTSTRAP_ADMIN_EMAIL` | first deploy |  |
| `OIP_BOOTSTRAP_ADMIN_PASSWORD` | first deploy |  |
| `OIP_MAIL_USERNAME` | when `OIP_MAIL_ENABLED=true` |  |
| `OIP_MAIL_PASSWORD` | when `OIP_MAIL_ENABLED=true` |  |
| `ANTHROPIC_API_KEY` | optional | Enables the AI features. |

Settings that aren't secret stay as plain environment variables in the task definition.

## Create the secret

```bash
jq -n --arg oip_db_url "<oip-db-url>" --arg oip_db_username "<oip-db-username>" --arg oip_db_password "<oip-db-password>" --arg oip_jwt_secret "<oip-jwt-secret>" --arg oip_data_encryption_key "<oip-data-encryption-key>"   '{OIP_DB_URL:$oip_db_url, OIP_DB_USERNAME:$oip_db_username, OIP_DB_PASSWORD:$oip_db_password, OIP_JWT_SECRET:$oip_jwt_secret, OIP_DATA_ENCRYPTION_KEY:$oip_data_encryption_key}'   | aws secretsmanager create-secret --region af-south-1 --name oip/prod --secret-string file:///dev/stdin
```

To change a key later, run `get-secret-value`, edit the JSON with `jq`, then run `put-secret-value`. Secrets are
read only at startup, so redeploy after a change.

## IAM: the task role

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "secretsmanager:GetSecretValue",
    "Resource": "arn:aws:secretsmanager:<region>:<account-id>:secret:oip/prod-*"
  }]
}
```

If the secret uses a customer-managed KMS key, also grant `kms:Decrypt` on it. Give each environment's role access
only to its own secret.

## Task definition (ECS) environment

```
SPRING_PROFILES_ACTIVE=aws
OIP_AWS_SECRET_ID=oip/prod
```
