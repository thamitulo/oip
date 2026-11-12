package za.co.oip.analytics;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.security.CurrentUser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reports from persisted data: market trends (global) and the tenant's own pipeline performance. */
@Tag(name = "Analytics")
@RestController
@RequestMapping("/api/v1/analytics")
public class ReportsController {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportsController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "Opportunities discovered per week, by sector (last N weeks)")
    @GetMapping("/opportunities")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> opportunities(@RequestParam(defaultValue = "12") int weeks) {
        return jdbc.queryForList("""
                select date_trunc('week', o.first_discovered_at at time zone 'Africa/Johannesburg')::date as week,
                       count(*) filter (where s.sector = 'GOVERNMENT' and not o.mining_related) as government,
                       count(*) filter (where s.sector = 'MINING' or o.mining_related) as mining,
                       count(*) as total
                from opportunities o join source_registry s on s.id = o.source_id
                where o.first_discovered_at >= now() - make_interval(weeks => :w) and o.duplicate_of_id is null
                group by 1 order by 1""", new MapSqlParameterSource("w", Math.clamp(weeks, 1, 104)));
    }

    @Operation(summary = "Active opportunities by procurement category and source category label")
    @GetMapping("/categories")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> categories() {
        return jdbc.queryForList("""
                select coalesce(source_category_label, procurement_category) as category, count(*) as count
                from opportunities where status in ('OPEN', 'UNKNOWN') and duplicate_of_id is null
                  and (closing_date is null or closing_date >= current_date)
                group by 1 order by 2 desc limit 30""", Map.of());
    }

    @Operation(summary = "Organisations with the most active opportunities")
    @GetMapping("/organisations")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> organisations() {
        return jdbc.queryForList("""
                select org.id, org.name, org.sector, count(*) as active
                from opportunities o join procurement_organisations org on org.id = o.organisation_id
                where o.status in ('OPEN', 'UNKNOWN') and (o.closing_date is null or o.closing_date >= current_date)
                group by 1, 2, 3 order by 4 desc limit 30""", Map.of());
    }

    @Operation(summary = "My pipeline: items per stage, submissions, win rate and value")
    @GetMapping("/pipeline")
    @Transactional(readOnly = true)
    public Map<String, Object> pipeline() {
        MapSqlParameterSource t = new MapSqlParameterSource("t", CurrentUser.require().tenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byStage", jdbc.queryForList("select stage, count(*) as count, coalesce(sum(bid_value), 0) as value from tenant_opportunities where tenant_id = :t group by 1", t));
        Map<String, Object> outcomes = jdbc.queryForMap("""
                select count(*) filter (where stage in ('SUBMITTED', 'AWARDED', 'UNSUCCESSFUL')) as submitted,
                       count(*) filter (where stage = 'AWARDED') as awarded,
                       count(*) filter (where stage = 'UNSUCCESSFUL') as unsuccessful,
                       coalesce(sum(bid_value) filter (where stage = 'AWARDED'), 0) as awarded_value
                from tenant_opportunities where tenant_id = :t""", t);
        long awarded = ((Number) outcomes.get("awarded")).longValue();
        long decided = awarded + ((Number) outcomes.get("unsuccessful")).longValue();
        outcomes.put("winRate", decided == 0 ? null : Math.round(1000.0 * awarded / decided) / 10.0);
        out.put("outcomes", outcomes);
        out.put("decisions", jdbc.queryForList("select decision, count(*) as count from bid_decisions where tenant_id = :t group by 1", t));
        out.put("tasks", jdbc.queryForMap("""
                select count(*) filter (where status = 'OPEN') as open, count(*) filter (where status = 'OPEN' and due_at < now()) as overdue,
                       count(*) filter (where status = 'DONE') as done from opportunity_tasks where tenant_id = :t""", t));
        return out;
    }
}
