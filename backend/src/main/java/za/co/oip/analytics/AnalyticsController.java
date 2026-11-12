package za.co.oip.analytics;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.crawling.adapter.ParsingSupport;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard figures computed from persisted data. Nothing here is estimated or sample data: when no
 * sources have been crawled the figures are zero.
 */
@Tag(name = "Analytics")
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private static final String ACTIVE = """
            o.status in ('OPEN', 'UNKNOWN') and o.duplicate_of_id is null
            and (o.closing_at is null or o.closing_at >= :now)
            and (o.closing_date is null or o.closing_date >= :today)""";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AnalyticsController(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Bucket(String key, String label, long count) {
    }

    public record SourceUpdate(UUID sourceId, String sourceName, Instant finishedAt, String outcome,
                               int recordsDiscovered, int recordsUpdated) {
    }

    public record DashboardDto(long activeOpportunities, long newToday, long closingWithin7Days, long miningActive,
                               long governmentActive, long sourcesEnabled, long sourcesHealthy,
                               Instant lastSuccessfulCrawlAt, List<Bucket> byProvince, List<Bucket> byCategory,
                               List<Bucket> byEventType, List<Bucket> bySubmissionMethod, List<Bucket> topOrganisations,
                               List<SourceUpdate> recentSourceUpdates) {
    }

    @Operation(summary = "Platform-wide opportunity dashboard figures")
    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    public DashboardDto dashboard() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(ParsingSupport.SOUTH_AFRICA));
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("today", today)
                .addValue("todayStart", Timestamp.from(today.atStartOfDay(ParsingSupport.SOUTH_AFRICA).toInstant()))
                .addValue("in7", today.plusDays(7));
        String from = " from opportunities o join source_registry s on s.id = o.source_id"
                + " left join procurement_organisations org on org.id = o.organisation_id where " + ACTIVE;

        long active = count("select count(*)" + from, p);
        long newToday = count("select count(*) from opportunities o where o.first_discovered_at >= :todayStart", p);
        long closing7 = count("select count(*)" + from + " and o.closing_date <= :in7", p);
        long mining = count("select count(*)" + from + " and (s.sector = 'MINING' or org.sector = 'MINING' or o.mining_related)", p);
        long government = count("select count(*)" + from + " and s.sector = 'GOVERNMENT' and coalesce(org.sector, 'GOVERNMENT') <> 'MINING'", p);
        long enabled = count("select count(*) from source_registry where enabled", p);
        long healthy = count("""
                select count(*) from source_registry s where s.enabled and s.last_successful_crawl_at is not null
                and s.last_successful_crawl_at >= coalesce(s.last_attempted_crawl_at, s.last_successful_crawl_at)""", p);
        Timestamp lastSuccess = jdbc.queryForObject("select max(last_successful_crawl_at) from source_registry", p, Timestamp.class);

        List<Bucket> byProvince = buckets("select coalesce(o.province, 'NOT_SPECIFIED') k, coalesce(o.province, 'Not specified') l, count(*) c"
                + from + " group by 1, 2 order by c desc", p);
        List<Bucket> byCategory = buckets("select o.procurement_category k, o.procurement_category l, count(*) c"
                + from + " group by 1, 2 order by c desc", p);
        List<Bucket> byEventType = buckets("select o.event_type k, o.event_type l, count(*) c"
                + from + " group by 1, 2 order by c desc", p);
        List<Bucket> bySubmission = buckets("select o.submission_method k, o.submission_method l, count(*) c"
                + from + " group by 1, 2 order by c desc", p);
        List<Bucket> topOrgs = buckets("select coalesce(org.id::text, 'UNKNOWN') k, coalesce(org.name, 'Unknown') l, count(*) c"
                + from + " group by 1, 2 order by c desc limit 10", p);
        List<SourceUpdate> updates = jdbc.query("""
                select r.source_id, s.name, r.finished_at, r.outcome, r.records_discovered, r.records_updated
                from crawl_job_runs r join source_registry s on s.id = r.source_id
                where r.finished_at is not null order by r.finished_at desc limit 10""", p,
                (rs, i) -> new SourceUpdate(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getString(4), rs.getInt(5), rs.getInt(6)));
        return new DashboardDto(active, newToday, closing7, mining, government, enabled, healthy,
                lastSuccess == null ? null : lastSuccess.toInstant(), byProvince, byCategory, byEventType, bySubmission, topOrgs, updates);
    }

    private long count(String sql, MapSqlParameterSource p) {
        Long n = jdbc.queryForObject(sql, p, Long.class);
        return n == null ? 0 : n;
    }

    private List<Bucket> buckets(String sql, MapSqlParameterSource p) {
        return jdbc.query(sql, p, (rs, i) -> new Bucket(rs.getString("k"), rs.getString("l"), rs.getLong("c")));
    }
}
