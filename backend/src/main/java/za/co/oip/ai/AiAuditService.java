package za.co.oip.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import za.co.oip.crawling.adapter.ParsingSupport;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Records every AI call (model, prompt version, tokens, latency, outcome) and enforces daily limits. */
@Service
public class AiAuditService {

    public enum Feature { ASSISTANT, CLASSIFICATION, MATCH_EXPLANATION }

    public enum Status { SUCCEEDED, FAILED, REFUSED, LIMITED }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AiAuditService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(UUID tenantId, UUID userId, Feature feature, String provider, String model, String promptVersion,
                       Status status, Long inputTokens, Long outputTokens, int toolCalls, long latencyMs, String error,
                       UUID subjectId) {
        jdbc.update("""
                insert into ai_processing_audit (id, tenant_id, user_id, feature, provider, model, prompt_version, status,
                  input_tokens, output_tokens, tool_calls, latency_ms, error, subject_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), tenantId, userId, feature.name(), provider, model, promptVersion, status.name(),
                inputTokens, outputTokens, toolCalls, latencyMs, error == null ? null : error.substring(0, Math.min(2000, error.length())),
                subjectId, Timestamp.from(clock.instant()));
    }

    /** Successful/attempted calls today (SAST) for a feature, optionally for one tenant. */
    public int countToday(Feature feature, UUID tenantId) {
        Timestamp start = Timestamp.from(LocalDate.now(clock.withZone(ParsingSupport.SOUTH_AFRICA))
                .atStartOfDay(ParsingSupport.SOUTH_AFRICA).toInstant());
        Integer n = tenantId == null
                ? jdbc.queryForObject("select count(*) from ai_processing_audit where feature = ? and created_at >= ? and status <> 'LIMITED'",
                Integer.class, feature.name(), start)
                : jdbc.queryForObject("select count(*) from ai_processing_audit where feature = ? and tenant_id = ? and created_at >= ? and status <> 'LIMITED'",
                Integer.class, feature.name(), tenantId, start);
        return n == null ? 0 : n;
    }

    /** Usage summary for administrators: last 30 days by day and feature. */
    public List<Map<String, Object>> usage() {
        return jdbc.queryForList("""
                select date_trunc('day', created_at at time zone 'Africa/Johannesburg')::date as day, feature, status,
                       count(*) as calls, coalesce(sum(input_tokens), 0) as input_tokens, coalesce(sum(output_tokens), 0) as output_tokens
                from ai_processing_audit where created_at >= now() - interval '30 days'
                group by 1, 2, 3 order by 1 desc, 2, 3""");
    }
}
