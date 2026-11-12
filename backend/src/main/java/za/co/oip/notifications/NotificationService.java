package za.co.oip.notifications;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.pipeline.PipelineService.AssignedEvent;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-app notifications with optional e-mail delivery. Each notification has a deduplication key that is unique
 * per user, so the same event is never notified twice.
 */
@Service
public class NotificationService {

    public enum Type {
        NEW_MATCH, CLOSING_SOON, CLOSING_DATE_CHANGED, NEW_DOCUMENT, STATUS_CHANGED, OPPORTUNITY_CHANGED, ASSIGNED, TASK_DUE,
        SOURCE_FAILURE, FOCUS_MATCH
    }

    /** Types that are e-mailed immediately when the user enabled urgent alerts, regardless of digest settings. */
    static final Set<Type> URGENT = Set.of(Type.CLOSING_SOON, Type.CLOSING_DATE_CHANGED, Type.TASK_DUE, Type.ASSIGNED, Type.SOURCE_FAILURE);

    public record NotificationDto(UUID id, Type type, String title, String body, String link, UUID opportunityId,
                                  Instant readAt, Instant createdAt) {
    }

    public record PreferencesDto(boolean inAppEnabled, boolean emailEnabled, String emailFrequency, int minMatchScore,
                                 boolean urgentAlerts, int closingReminderDays, String provinces, String eventTypes, String sectors) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public NotificationService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Creates a notification unless one with the same key exists for the user. Returns true when created. Also queues
     * an immediate e-mail when the user's preferences ask for it.
     */
    @Transactional
    public boolean notify(UUID tenantId, UUID userId, Type type, String title, String body, String link, UUID opportunityId,
                          String dedupKey) {
        PreferencesDto prefs = preferences(tenantId, userId);
        if (!prefs.inAppEnabled() && !prefs.emailEnabled()) {
            return false;
        }
        UUID id = UUID.randomUUID();
        int created = jdbc.update("""
                insert into notifications (id, tenant_id, user_id, type, title, body, link, opportunity_id, dedup_key, created_at)
                values (:id, :t, :u, :type, :title, :body, :link, :o, :k, :now) on conflict (user_id, dedup_key) do nothing""",
                new MapSqlParameterSource("id", id).addValue("t", tenantId).addValue("u", userId).addValue("type", type.name())
                        .addValue("title", truncate(title, 300)).addValue("body", truncate(body, 2000)).addValue("link", link)
                        .addValue("o", opportunityId).addValue("k", truncate(dedupKey, 300)).addValue("now", now()));
        if (created == 0) {
            return false;
        }
        boolean immediate = prefs.emailEnabled() && ("IMMEDIATE".equals(prefs.emailFrequency())
                || (prefs.urgentAlerts() && URGENT.contains(type) && !"OFF".equals(prefs.emailFrequency())));
        if (immediate) {
            jdbc.update("insert into notification_deliveries (id, notification_id, channel, status, created_at) values (:id, :n, 'EMAIL', 'PENDING', :now)",
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("n", id).addValue("now", now()));
        }
        return true;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onAssigned(AssignedEvent e) {
        notify(e.tenantId(), e.assigneeId(), Type.ASSIGNED, "Assigned to you: " + e.opportunityTitle(),
                "You have been assigned this opportunity in the bid pipeline.", "/opportunities/" + e.opportunityId(),
                e.opportunityId(), "assigned:" + e.opportunityId() + ":" + clock.instant().toEpochMilli());
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> list(UUID tenantId, UUID userId, boolean unreadOnly, int limit) {
        return jdbc.query("""
                select id, type, title, body, link, opportunity_id, read_at, created_at from notifications
                where tenant_id = :t and user_id = :u""" + (unreadOnly ? " and read_at is null" : "") + " order by created_at desc limit :l",
                new MapSqlParameterSource("t", tenantId).addValue("u", userId).addValue("l", limit),
                (rs, i) -> new NotificationDto(rs.getObject(1, UUID.class), Type.valueOf(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), inst(rs.getTimestamp(7)), inst(rs.getTimestamp(8))));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID tenantId, UUID userId) {
        Long n = jdbc.queryForObject("select count(*) from notifications where tenant_id = :t and user_id = :u and read_at is null",
                new MapSqlParameterSource("t", tenantId).addValue("u", userId), Long.class);
        return n == null ? 0 : n;
    }

    @Transactional
    public void markRead(UUID tenantId, UUID userId, UUID id) {
        jdbc.update("update notifications set read_at = coalesce(read_at, :now) where id = :id and tenant_id = :t and user_id = :u",
                new MapSqlParameterSource("id", id).addValue("t", tenantId).addValue("u", userId).addValue("now", now()));
    }

    @Transactional
    public void markAllRead(UUID tenantId, UUID userId) {
        jdbc.update("update notifications set read_at = :now where tenant_id = :t and user_id = :u and read_at is null",
                new MapSqlParameterSource("t", tenantId).addValue("u", userId).addValue("now", now()));
    }

    @Transactional(readOnly = true)
    public PreferencesDto preferences(UUID tenantId, UUID userId) {
        List<PreferencesDto> p = jdbc.query("""
                select in_app_enabled, email_enabled, email_frequency, min_match_score, urgent_alerts, closing_reminder_days,
                       provinces, event_types, sectors from notification_preferences where tenant_id = :t and user_id = :u""",
                new MapSqlParameterSource("t", tenantId).addValue("u", userId),
                (rs, i) -> new PreferencesDto(rs.getBoolean(1), rs.getBoolean(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5),
                        rs.getInt(6), rs.getString(7), rs.getString(8), rs.getString(9)));
        return p.isEmpty() ? new PreferencesDto(true, true, "DAILY_DIGEST", 60, true, 3, null, null, null) : p.getFirst();
    }

    @Transactional
    public PreferencesDto updatePreferences(UUID tenantId, UUID userId, PreferencesDto d) {
        if (!Set.of("IMMEDIATE", "DAILY_DIGEST", "OFF").contains(d.emailFrequency())) {
            throw new BusinessRuleException("Email frequency must be IMMEDIATE, DAILY_DIGEST or OFF");
        }
        if (d.minMatchScore() < 0 || d.minMatchScore() > 100 || d.closingReminderDays() < 0 || d.closingReminderDays() > 30) {
            throw new BusinessRuleException("Minimum score must be 0-100 and reminder days 0-30");
        }
        jdbc.update("""
                insert into notification_preferences (tenant_id, user_id, in_app_enabled, email_enabled, email_frequency, min_match_score,
                  urgent_alerts, closing_reminder_days, provinces, event_types, sectors, updated_at)
                values (:t, :u, :inApp, :email, :freq, :min, :urgent, :days, :prov, :types, :sectors, :now)
                on conflict (tenant_id, user_id) do update set in_app_enabled = excluded.in_app_enabled, email_enabled = excluded.email_enabled,
                  email_frequency = excluded.email_frequency, min_match_score = excluded.min_match_score, urgent_alerts = excluded.urgent_alerts,
                  closing_reminder_days = excluded.closing_reminder_days, provinces = excluded.provinces, event_types = excluded.event_types,
                  sectors = excluded.sectors, updated_at = excluded.updated_at""",
                new MapSqlParameterSource("t", tenantId).addValue("u", userId).addValue("inApp", d.inAppEnabled())
                        .addValue("email", d.emailEnabled()).addValue("freq", d.emailFrequency()).addValue("min", d.minMatchScore())
                        .addValue("urgent", d.urgentAlerts()).addValue("days", d.closingReminderDays()).addValue("prov", blank(d.provinces()))
                        .addValue("types", blank(d.eventTypes())).addValue("sectors", blank(d.sectors())).addValue("now", now()));
        return preferences(tenantId, userId);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static Instant inst(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
