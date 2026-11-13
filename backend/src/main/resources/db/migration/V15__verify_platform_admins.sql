-- Platform administrators are created from operator configuration (OIP_BOOTSTRAP_ADMIN_EMAIL); their address is
-- treated as confirmed, as for newly bootstrapped admins.
UPDATE users SET email_verified_at = created_at WHERE platform_admin AND email_verified_at IS NULL;
