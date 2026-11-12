-- =====================================================================
-- V11: Cross-instance rate limiting and retention settings.
-- =====================================================================

-- Fixed-window counters shared by all API instances (replaces the per-instance in-memory limiter).
CREATE TABLE rate_limit_counters (
    limiter_key   VARCHAR(200) NOT NULL,
    window_start  TIMESTAMPTZ  NOT NULL,
    hits          INT          NOT NULL,
    PRIMARY KEY (limiter_key, window_start)
);
CREATE INDEX ix_rate_limit_counters_window ON rate_limit_counters (window_start);

INSERT INTO system_settings (setting_key, setting_value, description, updated_at) VALUES
 ('retention.audit_events_days', '730', 'Days to keep audit events', NOW()),
 ('retention.notifications_days', '180', 'Days to keep notifications', NOW()),
 ('retention.observation_excerpts_days', '180', 'Days to keep fetched-content excerpts on source observations', NOW()),
 ('retention.sessions_days', '30', 'Days to keep expired or revoked sessions', NOW())
ON CONFLICT (setting_key) DO NOTHING;
