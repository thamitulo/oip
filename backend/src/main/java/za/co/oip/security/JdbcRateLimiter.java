package za.co.oip.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Fixed-window rate limiter backed by PostgreSQL, so the limit holds across all API instances without Redis.
 * One atomic upsert per request.
 */
@Component
public class JdbcRateLimiter {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcRateLimiter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Records a hit and returns whether the caller is still within {@code limit} hits for the current minute. */
    public boolean tryAcquire(String key, int limit) {
        Instant window = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        Integer hits = jdbc.queryForObject("""
                insert into rate_limit_counters (limiter_key, window_start, hits) values (?, ?, 1)
                on conflict (limiter_key, window_start) do update set hits = rate_limit_counters.hits + 1
                returning hits""", Integer.class, key, Timestamp.from(window));
        return hits != null && hits <= limit;
    }

    /** Removes old windows. */
    public int purge() {
        return jdbc.update("delete from rate_limit_counters where window_start < ?",
                Timestamp.from(clock.instant().minus(10, ChronoUnit.MINUTES)));
    }
}
