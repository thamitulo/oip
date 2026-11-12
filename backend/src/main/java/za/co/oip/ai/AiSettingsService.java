package za.co.oip.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;

import java.time.Clock;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runtime AI configuration stored in {@code system_settings} and editable by platform administrators.
 * The provider API key is never stored here: it comes only from the environment ({@code ANTHROPIC_API_KEY}).
 */
@Service
public class AiSettingsService {

    public static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");
    private static final Pattern MODEL_ID = Pattern.compile("^claude-[a-z0-9.-]{3,60}$");

    public record AiSettings(boolean enabled, String model, String effort, boolean assistantEnabled,
                             boolean classificationEnabled, int classificationDailyLimit, int assistantDailyLimitPerTenant,
                             boolean matchExplanationEnabled, boolean serverSideFallback) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final Clock clock;

    public AiSettingsService(JdbcTemplate jdbc, AuditService auditService, Clock clock) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AiSettings get() {
        Map<String, String> v = new HashMap<>();
        jdbc.query("select setting_key, setting_value from system_settings where setting_key like 'ai.%'",
                rs -> { v.put(rs.getString(1), rs.getString(2)); });
        return new AiSettings(
                bool(v, "ai.enabled", true),
                v.getOrDefault("ai.model", "claude-opus-5-5"),
                v.getOrDefault("ai.effort", "medium"),
                bool(v, "ai.assistant.enabled", true),
                bool(v, "ai.classification.enabled", false),
                integer(v, "ai.classification.daily_limit", 200),
                integer(v, "ai.assistant.daily_limit_per_tenant", 200),
                bool(v, "ai.match_explanation.enabled", true),
                bool(v, "ai.server_side_fallback", true));
    }

    @Transactional
    public AiSettings update(AiSettings s) {
        if (!MODEL_ID.matcher(s.model()).matches()) {
            throw new BusinessRuleException("Model must be a Claude model id such as claude-opus-5-5");
        }
        if (!EFFORTS.contains(s.effort())) {
            throw new BusinessRuleException("Effort must be one of " + EFFORTS);
        }
        if (s.classificationDailyLimit() < 0 || s.assistantDailyLimitPerTenant() < 0) {
            throw new BusinessRuleException("Limits cannot be negative");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ai.enabled", String.valueOf(s.enabled()));
        values.put("ai.model", s.model());
        values.put("ai.effort", s.effort());
        values.put("ai.assistant.enabled", String.valueOf(s.assistantEnabled()));
        values.put("ai.classification.enabled", String.valueOf(s.classificationEnabled()));
        values.put("ai.classification.daily_limit", String.valueOf(s.classificationDailyLimit()));
        values.put("ai.assistant.daily_limit_per_tenant", String.valueOf(s.assistantDailyLimitPerTenant()));
        values.put("ai.match_explanation.enabled", String.valueOf(s.matchExplanationEnabled()));
        values.put("ai.server_side_fallback", String.valueOf(s.serverSideFallback()));
        Timestamp now = Timestamp.from(clock.instant());
        values.forEach((k, val) -> jdbc.update("""
                insert into system_settings (setting_key, setting_value, updated_at) values (?, ?, ?)
                on conflict (setting_key) do update set setting_value = excluded.setting_value, updated_at = excluded.updated_at""",
                k, val, now));
        auditService.record(null, "AI_SETTINGS_UPDATED", "SystemSettings", "ai", Map.of("model", s.model(), "effort", s.effort(),
                "enabled", s.enabled()));
        return get();
    }

    private static boolean bool(Map<String, String> v, String k, boolean def) {
        return v.containsKey(k) ? Boolean.parseBoolean(v.get(k)) : def;
    }

    private static int integer(Map<String, String> v, String k, int def) {
        try {
            return v.containsKey(k) ? Integer.parseInt(v.get(k)) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
