package za.co.oip.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.notifications.NotificationService.PreferencesDto;
import za.co.oip.notifications.NotificationService.Type;
import za.co.oip.pipeline.PipelineService;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Generates notifications from matches, closing dates, changes and tasks, and delivers e-mails. */
@Component
@ConditionalOnProperty(prefix = "oip.notifications", name = "jobs-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationJobs {

    private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationService notifications;
    private final PipelineService pipeline;
    private final JavaMailSender mailSender;
    private final Clock clock;
    private final boolean mailEnabled;
    private final String mailFrom;
    private final String appUrl;

    public NotificationJobs(NamedParameterJdbcTemplate jdbc, NotificationService notifications, PipelineService pipeline,
                            JavaMailSender mailSender, Clock clock,
                            @Value("${oip.mail.enabled:false}") boolean mailEnabled,
                            @Value("${oip.mail.from:no-reply@oip.local}") String mailFrom,
                            @Value("${oip.app-url:http://localhost:4200}") String appUrl) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.pipeline = pipeline;
        this.mailSender = mailSender;
        this.clock = clock;
        this.mailEnabled = mailEnabled;
        this.mailFrom = mailFrom;
        this.appUrl = appUrl;
    }

    @Scheduled(initialDelayString = "PT4M", fixedDelayString = "${oip.notifications.interval:PT15M}")
    public void generate() {
        int expired = pipeline.expireClosed();
        int n = newMatches() + closingSoon() + materialChanges() + tasksDue() + sourceFailures();
        if (n > 0 || expired > 0) {
            log.info("Created {} notification(s); expired {} pipeline item(s)", n, expired);
        }
    }

    /** New opportunities (discovered in the last 2 days) whose match meets each user's minimum score. */
    int newMatches() {
        int count = 0;
        for (Member m : members()) {
            PreferencesDto p = notifications.preferences(m.tenantId(), m.userId());
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select o.id, o.title, m.score, o.province, o.event_type,
                           coalesce(case when org.sector = 'MINING' or s.sector = 'MINING' or o.mining_related then 'MINING' end, s.sector) sector
                    from opportunity_matches m join opportunities o on o.id = m.opportunity_id
                    join source_registry s on s.id = o.source_id left join procurement_organisations org on org.id = o.organisation_id
                    where m.tenant_id = :t and m.score >= :min and o.first_discovered_at >= :since and o.status in ('OPEN', 'UNKNOWN')
                    order by m.score desc limit 30""",
                    new MapSqlParameterSource("t", m.tenantId()).addValue("min", p.minMatchScore())
                            .addValue("since", Timestamp.from(clock.instant().minus(Duration.ofDays(2)))));
            for (Map<String, Object> r : rows) {
                if (!allowed(p.provinces(), (String) r.get("province")) || !allowed(p.eventTypes(), (String) r.get("event_type"))
                        || !allowed(p.sectors(), (String) r.get("sector"))) continue;
                UUID id = (UUID) r.get("id");
                if (notifications.notify(m.tenantId(), m.userId(), Type.NEW_MATCH, "New match (" + r.get("score") + "%): " + r.get("title"),
                        "A newly published opportunity matches your company profile.", "/opportunities/" + id, id, "match:" + id)) count++;
            }
        }
        return count;
    }

    /** Pipeline items closing within each user's reminder window. */
    int closingSoon() {
        int count = 0;
        LocalDate today = LocalDate.now(clock.withZone(ParsingSupport.SOUTH_AFRICA));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select t.tenant_id, t.assigned_to, o.id, o.title, o.closing_date from tenant_opportunities t
                join opportunities o on o.id = t.opportunity_id
                where t.stage not in ('SUBMITTED', 'AWARDED', 'UNSUCCESSFUL', 'WITHDRAWN', 'EXPIRED')
                  and o.closing_date between :today and :until""",
                new MapSqlParameterSource("today", today).addValue("until", today.plusDays(30)));
        for (Map<String, Object> r : rows) {
            UUID tenant = (UUID) r.get("tenant_id");
            LocalDate closing = ((java.sql.Date) r.get("closing_date")).toLocalDate();
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, closing);
            for (UUID user : recipients(tenant, (UUID) r.get("assigned_to"))) {
                if (days > notifications.preferences(tenant, user).closingReminderDays()) continue;
                UUID id = (UUID) r.get("id");
                if (notifications.notify(tenant, user, Type.CLOSING_SOON,
                        (days == 0 ? "Closes today: " : "Closes in " + days + " day(s): ") + r.get("title"),
                        "Closing date " + closing + ". Check that your submission is on track.", "/opportunities/" + id, id,
                        "closing:" + id + ":" + closing)) count++;
            }
        }
        return count;
    }

    /** Material source changes (closing date, documents, status ...) to opportunities in a tenant's pipeline. */
    int materialChanges() {
        int count = 0;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select e.id event_id, e.field_name, e.old_value, e.new_value, t.tenant_id, t.assigned_to, o.id, o.title
                from source_change_events e join tenant_opportunities t on t.opportunity_id = e.opportunity_id
                join opportunities o on o.id = e.opportunity_id
                where e.material and e.detected_at >= :since
                  and t.stage not in ('AWARDED', 'UNSUCCESSFUL', 'WITHDRAWN', 'EXPIRED')""",
                new MapSqlParameterSource("since", Timestamp.from(clock.instant().minus(Duration.ofDays(2)))));
        for (Map<String, Object> r : rows) {
            String field = (String) r.get("field_name");
            Type type = switch (field) {
                case "closingDate", "closingAt" -> Type.CLOSING_DATE_CHANGED;
                case "documents" -> Type.NEW_DOCUMENT;
                case "status" -> Type.STATUS_CHANGED;
                default -> Type.OPPORTUNITY_CHANGED;
            };
            String body = field + ": " + (r.get("old_value") == null ? "—" : r.get("old_value")) + " → "
                    + (r.get("new_value") == null ? "—" : r.get("new_value"));
            UUID id = (UUID) r.get("id");
            for (UUID user : recipients((UUID) r.get("tenant_id"), (UUID) r.get("assigned_to"))) {
                if (notifications.notify((UUID) r.get("tenant_id"), user, type, "Changed at source: " + r.get("title"), body,
                        "/opportunities/" + id, id, "change:" + r.get("event_id"))) count++;
            }
        }
        return count;
    }

    /** Tasks due within 24 hours, or whose reminder time has passed. */
    int tasksDue() {
        int count = 0;
        Timestamp now = Timestamp.from(clock.instant());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select k.id, k.tenant_id, k.title, k.due_at, k.assignee_id, k.created_by, x.opportunity_id from opportunity_tasks k
                join tenant_opportunities x on x.id = k.tenant_opportunity_id
                where k.status = 'OPEN' and k.reminder_sent_at is null
                  and ((k.remind_at is not null and k.remind_at <= :now) or (k.due_at is not null and k.due_at <= :soon))""",
                new MapSqlParameterSource("now", now).addValue("soon", Timestamp.from(clock.instant().plus(Duration.ofHours(24)))));
        for (Map<String, Object> r : rows) {
            UUID user = r.get("assignee_id") != null ? (UUID) r.get("assignee_id") : (UUID) r.get("created_by");
            UUID opp = (UUID) r.get("opportunity_id");
            if (notifications.notify((UUID) r.get("tenant_id"), user, Type.TASK_DUE, "Task due: " + r.get("title"),
                    "Due " + r.get("due_at"), "/opportunities/" + opp, opp, "task:" + r.get("id"))) count++;
            jdbc.update("update opportunity_tasks set reminder_sent_at = :now where id = :id", new MapSqlParameterSource("id", r.get("id")).addValue("now", now));
        }
        return count;
    }

    /** Platform administrators hear about sources that failed twice in a row. */
    int sourceFailures() {
        int count = 0;
        List<Map<String, Object>> failing = jdbc.queryForList("""
                select s.id, s.name, c.consecutive_failures from crawl_schedules c join source_registry s on s.id = c.source_id
                where s.enabled and c.consecutive_failures >= 2""", Map.of());
        if (failing.isEmpty()) return 0;
        List<Map<String, Object>> admins = jdbc.queryForList("""
                select u.id user_id, min(tu.tenant_id::text)::uuid tenant_id from users u join tenant_users tu on tu.user_id = u.id
                where u.platform_admin and u.status = 'ACTIVE' group by u.id""", Map.of());
        String day = LocalDate.now(clock.withZone(ParsingSupport.SOUTH_AFRICA)).toString();
        for (Map<String, Object> s : failing) {
            for (Map<String, Object> a : admins) {
                if (notifications.notify((UUID) a.get("tenant_id"), (UUID) a.get("user_id"), Type.SOURCE_FAILURE,
                        "Source failing: " + s.get("name"), s.get("consecutive_failures") + " consecutive failed crawls.",
                        "/admin/sources/" + s.get("id"), null, "source:" + s.get("id") + ":" + day)) count++;
            }
        }
        return count;
    }

    /** Sends queued immediate e-mails. */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void deliverEmails() {
        List<Map<String, Object>> pending = jdbc.queryForList("""
                select d.id, n.title, n.body, n.link, u.email from notification_deliveries d join notifications n on n.id = d.notification_id
                join users u on u.id = n.user_id where d.status = 'PENDING' and d.channel = 'EMAIL' and d.attempts < 3 limit 50""", Map.of());
        for (Map<String, Object> d : pending) {
            String status;
            String error = null;
            if (!mailEnabled) {
                status = "SKIPPED";
                error = "E-mail delivery is disabled (OIP_MAIL_ENABLED=false)";
            } else {
                try {
                    send((String) d.get("email"), (String) d.get("title"), d.get("body") + "\n\n" + appUrl + d.get("link"));
                    status = "SENT";
                } catch (RuntimeException e) {
                    status = "FAILED";
                    error = e.getMessage();
                }
            }
            jdbc.update("""
                    update notification_deliveries set status = case when :s = 'FAILED' and attempts < 2 then 'PENDING' else :s end,
                      attempts = attempts + 1, sent_at = case when :s = 'SENT' then :now else null end, error = :e where id = :id""",
                    new MapSqlParameterSource("s", status).addValue("e", error).addValue("id", d.get("id"))
                            .addValue("now", Timestamp.from(clock.instant())));
        }
    }

    /** Daily digest at 07:00 SAST of the last day's unread notifications not already e-mailed. */
    @Scheduled(cron = "0 0 7 * * *", zone = "Africa/Johannesburg")
    public void dailyDigest() {
        List<Map<String, Object>> users = jdbc.queryForList("""
                select distinct n.user_id, n.tenant_id, u.email from notifications n join users u on u.id = n.user_id
                left join notification_preferences p on p.user_id = n.user_id and p.tenant_id = n.tenant_id
                where n.created_at >= now() - interval '1 day' and n.read_at is null
                  and coalesce(p.email_enabled, true) and coalesce(p.email_frequency, 'DAILY_DIGEST') = 'DAILY_DIGEST'
                  and not exists (select 1 from notification_deliveries d where d.notification_id = n.id and d.channel = 'EMAIL')""", Map.of());
        for (Map<String, Object> u : users) {
            List<Map<String, Object>> items = jdbc.queryForList("""
                    select n.id, n.title, n.link from notifications n where n.user_id = :u and n.tenant_id = :t and n.read_at is null
                      and n.created_at >= now() - interval '1 day'
                      and not exists (select 1 from notification_deliveries d where d.notification_id = n.id and d.channel = 'EMAIL')
                    order by n.created_at desc limit 50""", new MapSqlParameterSource("u", u.get("user_id")).addValue("t", u.get("tenant_id")));
            if (items.isEmpty()) continue;
            StringBuilder body = new StringBuilder("Your procurement updates:\n\n");
            items.forEach(i -> body.append("• ").append(i.get("title")).append("\n  ").append(appUrl).append(i.get("link")).append("\n"));
            String status = "SKIPPED";
            String error = "E-mail delivery is disabled (OIP_MAIL_ENABLED=false)";
            if (mailEnabled) {
                try {
                    send((String) u.get("email"), items.size() + " procurement update(s)", body.toString());
                    status = "SENT";
                    error = null;
                } catch (RuntimeException e) {
                    status = "FAILED";
                    error = e.getMessage();
                }
            }
            for (Map<String, Object> i : items) {
                jdbc.update("""
                        insert into notification_deliveries (id, notification_id, channel, status, attempts, sent_at, error, created_at)
                        values (:id, :n, 'EMAIL', :s, 1, :sent, :e, :now) on conflict do nothing""",
                        new MapSqlParameterSource("id", UUID.randomUUID()).addValue("n", i.get("id")).addValue("s", status)
                                .addValue("sent", "SENT".equals(status) ? Timestamp.from(clock.instant()) : null)
                                .addValue("e", error).addValue("now", Timestamp.from(clock.instant())));
            }
        }
    }

    private void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(to);
        message.setSubject("[OIP] " + subject);
        message.setText(text);
        mailSender.send(message);
    }

    private record Member(UUID tenantId, UUID userId) {
    }

    private List<Member> members() {
        return jdbc.query("""
                select tu.tenant_id, tu.user_id from tenant_users tu join users u on u.id = tu.user_id join tenants t on t.id = tu.tenant_id
                where tu.status = 'ACTIVE' and u.status = 'ACTIVE' and t.status = 'ACTIVE'""", Map.of(),
                (rs, i) -> new Member(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
    }

    /** The assignee, or every member allowed to edit the pipeline when unassigned. */
    private List<UUID> recipients(UUID tenantId, UUID assignee) {
        if (assignee != null) return List.of(assignee);
        return jdbc.queryForList("""
                select tu.user_id from tenant_users tu where tu.tenant_id = :t and tu.status = 'ACTIVE'
                  and tu.role_code in ('TENANT_OWNER', 'TENANT_ADMIN', 'PROCUREMENT_MANAGER', 'BID_MANAGER')""",
                new MapSqlParameterSource("t", tenantId), UUID.class);
    }

    private static boolean allowed(String csv, String value) {
        if (csv == null || csv.isBlank()) return true;
        return value != null && Arrays.stream(csv.split(",")).map(String::trim).anyMatch(value::equalsIgnoreCase);
    }
}
