package za.co.oip.administration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.oip.security.JdbcRateLimiter;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

/** Applies the retention periods configured in {@code system_settings} (see V11). Runs nightly. */
@Component
public class RetentionJobs {

    private static final Logger log = LoggerFactory.getLogger(RetentionJobs.class);

    private final JdbcTemplate jdbc;
    private final JdbcRateLimiter rateLimiter;
    private final Clock clock;

    public RetentionJobs(JdbcTemplate jdbc, JdbcRateLimiter rateLimiter, Clock clock) {
        this.jdbc = jdbc;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "Africa/Johannesburg")
    public void apply() {
        int sessions = jdbc.update("delete from user_sessions where (revoked_at is not null or expires_at < now()) and created_at < ?",
                before("retention.sessions_days", 30));
        int notifications = jdbc.update("delete from notifications where created_at < ?", before("retention.notifications_days", 180));
        int excerpts = jdbc.update("update source_observations set excerpt = null where excerpt is not null and fetched_at < ?",
                before("retention.observation_excerpts_days", 180));
        int audits = jdbc.update("delete from audit_events where occurred_at < ?", before("retention.audit_events_days", 730));
        log.info("Retention: removed {} sessions, {} notifications, {} audit events; cleared {} observation excerpts",
                sessions, notifications, audits, excerpts);
    }

    @Scheduled(fixedDelayString = "PT5M")
    public void purgeRateLimits() {
        rateLimiter.purge();
    }

    private Timestamp before(String key, int defaultDays) {
        Integer days;
        try {
            days = jdbc.queryForObject("select cast(setting_value as int) from system_settings where setting_key = ?", Integer.class, key);
        } catch (RuntimeException e) {
            days = null;
        }
        return Timestamp.from(clock.instant().minus(Duration.ofDays(days == null ? defaultDays : Math.max(1, days))));
    }
}
