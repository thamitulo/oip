package za.co.oip.pipeline;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.opportunities.OpportunityQueryService;
import za.co.oip.opportunities.api.OpportunityDtos.OpportunitySummaryDto;
import za.co.oip.security.AuthenticatedUser;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A tenant's bid pipeline. Each record is the tenant's relationship with a global opportunity; nothing here ever
 * changes the global opportunity. All queries are scoped by the caller's tenant.
 */
@Service
public class PipelineService {

    public enum Stage {
        DISCOVERED, UNDER_REVIEW, SHORTLISTED, GO_NO_GO, DOCUMENTS_BEING_PREPARED, BID_IN_PROGRESS, SUBMITTED, AWARDED,
        UNSUCCESSFUL, WITHDRAWN, EXPIRED;

        public static final Set<Stage> FINAL = EnumSet.of(SUBMITTED, AWARDED, UNSUCCESSFUL, WITHDRAWN, EXPIRED);
        public static final Set<Stage> EXPIRABLE = EnumSet.of(DISCOVERED, UNDER_REVIEW, SHORTLISTED, GO_NO_GO);
    }

    public enum Priority { LOW, MEDIUM, HIGH }

    /** Published when a pipeline item is assigned (notifications listen for it). */
    public record AssignedEvent(UUID tenantId, UUID assigneeId, UUID opportunityId, String opportunityTitle, UUID actorId) {
    }

    public record PipelineItemDto(UUID id, UUID opportunityId, Stage stage, Priority priority, UUID assignedTo,
                                  String assignedToName, Instant submittedAt, BigDecimal bidValue, String outcomeNotes,
                                  int openTasks, Integer matchScore, Instant updatedAt, OpportunitySummaryDto opportunity) {
    }

    public record NoteDto(UUID id, String body, UUID authorId, String authorName, Instant createdAt) {
    }

    public record TaskDto(UUID id, UUID opportunityId, String opportunityTitle, String title, Instant dueAt, UUID assigneeId,
                          String assigneeName, String status, Instant remindAt, Instant createdAt, Instant completedAt) {
    }

    public record DecisionDto(UUID id, String decision, String rationale, UUID decidedBy, String decidedByName, Instant decidedAt) {
    }

    public record HistoryDto(String eventType, String fromValue, String toValue, String comment, String actorName, Instant occurredAt) {
    }

    public record PipelineDetailDto(PipelineItemDto item, List<NoteDto> notes, List<TaskDto> tasks, List<DecisionDto> decisions,
                                    List<HistoryDto> history) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final OpportunityQueryService opportunities;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PipelineService(NamedParameterJdbcTemplate jdbc, OpportunityQueryService opportunities, AuditService auditService,
                           ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.opportunities = opportunities;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public PipelineItemDto shortlist(AuthenticatedUser actor, UUID opportunityId, String comment) {
        requireOpportunity(opportunityId);
        UUID id = findItemId(actor.tenantId(), opportunityId);
        if (id == null) {
            id = UUID.randomUUID();
            Timestamp now = now();
            jdbc.update("""
                    insert into tenant_opportunities (id, tenant_id, opportunity_id, stage, priority, created_at, updated_at, created_by, updated_by)
                    values (:id, :t, :o, 'SHORTLISTED', 'MEDIUM', :now, :now, :u, :u)""",
                    params(actor).addValue("id", id).addValue("o", opportunityId).addValue("now", now));
            history(actor, id, "SHORTLISTED", null, Stage.SHORTLISTED.name(), comment);
            auditService.record(actor.tenantId(), "PIPELINE_SHORTLISTED", "Opportunity", opportunityId, Map.of());
        }
        return item(actor.tenantId(), opportunityId);
    }

    @Transactional
    public PipelineItemDto changeStage(AuthenticatedUser actor, UUID opportunityId, Stage stage, String comment) {
        UUID id = requireItem(actor.tenantId(), opportunityId);
        String from = jdbc.queryForObject("select stage from tenant_opportunities where id = :id", Map.of("id", id), String.class);
        if (stage.name().equals(from)) {
            return item(actor.tenantId(), opportunityId);
        }
        jdbc.update("""
                update tenant_opportunities set stage = :stage, updated_at = :now, updated_by = :u, version = version + 1,
                  submitted_at = case when :stage = 'SUBMITTED' then coalesce(submitted_at, :now) else submitted_at end
                where id = :id and tenant_id = :t""",
                params(actor).addValue("id", id).addValue("stage", stage.name()).addValue("now", now()));
        history(actor, id, "STAGE_CHANGED", from, stage.name(), comment);
        return item(actor.tenantId(), opportunityId);
    }

    @Transactional
    public PipelineItemDto assign(AuthenticatedUser actor, UUID opportunityId, UUID assigneeId) {
        UUID id = requireItem(actor.tenantId(), opportunityId);
        if (assigneeId != null && !isMember(actor.tenantId(), assigneeId)) {
            throw new BusinessRuleException("The assignee is not a member of your business");
        }
        jdbc.update("update tenant_opportunities set assigned_to = :a, updated_at = :now, updated_by = :u, version = version + 1 where id = :id and tenant_id = :t",
                params(actor).addValue("id", id).addValue("a", assigneeId).addValue("now", now()));
        history(actor, id, "ASSIGNED", null, assigneeId == null ? "Unassigned" : userName(assigneeId), null);
        if (assigneeId != null && !assigneeId.equals(actor.userId())) {
            PipelineItemDto item = item(actor.tenantId(), opportunityId);
            events.publishEvent(new AssignedEvent(actor.tenantId(), assigneeId, opportunityId,
                    item.opportunity() == null ? "an opportunity" : item.opportunity().title(), actor.userId()));
        }
        return item(actor.tenantId(), opportunityId);
    }

    @Transactional
    public PipelineItemDto updateDetails(AuthenticatedUser actor, UUID opportunityId, Priority priority, BigDecimal bidValue,
                                         String outcomeNotes) {
        UUID id = requireItem(actor.tenantId(), opportunityId);
        jdbc.update("""
                update tenant_opportunities set priority = coalesce(:p, priority), bid_value = :v, outcome_notes = :n,
                  updated_at = :now, updated_by = :u, version = version + 1 where id = :id and tenant_id = :t""",
                params(actor).addValue("id", id).addValue("p", priority == null ? null : priority.name()).addValue("v", bidValue)
                        .addValue("n", outcomeNotes).addValue("now", now()));
        history(actor, id, "DETAILS_UPDATED", null, priority == null ? null : "Priority " + priority, null);
        return item(actor.tenantId(), opportunityId);
    }

    @Transactional
    public NoteDto addNote(AuthenticatedUser actor, UUID opportunityId, String body) {
        UUID itemId = requireItem(actor.tenantId(), opportunityId);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into opportunity_notes (id, tenant_id, tenant_opportunity_id, author_id, body, created_at) values (:id, :t, :i, :u, :b, :now)",
                params(actor).addValue("id", id).addValue("i", itemId).addValue("b", body.trim()).addValue("now", now()));
        touch(itemId);
        return notes(actor.tenantId(), itemId).stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public TaskDto addTask(AuthenticatedUser actor, UUID opportunityId, String title, Instant dueAt, UUID assigneeId, Instant remindAt) {
        UUID itemId = requireItem(actor.tenantId(), opportunityId);
        if (assigneeId != null && !isMember(actor.tenantId(), assigneeId)) {
            throw new BusinessRuleException("The assignee is not a member of your business");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into opportunity_tasks (id, tenant_id, tenant_opportunity_id, title, due_at, assignee_id, status, remind_at, created_by, created_at)
                values (:id, :t, :i, :title, :due, :a, 'OPEN', :remind, :u, :now)""",
                params(actor).addValue("id", id).addValue("i", itemId).addValue("title", title.trim())
                        .addValue("due", ts(dueAt)).addValue("a", assigneeId).addValue("remind", ts(remindAt)).addValue("now", now()));
        history(actor, itemId, "TASK_ADDED", null, title.trim(), null);
        return tasks(actor.tenantId(), itemId, null).stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public TaskDto updateTask(AuthenticatedUser actor, UUID taskId, String status) {
        if (!Set.of("OPEN", "DONE", "CANCELLED").contains(status)) {
            throw new BusinessRuleException("Unknown task status " + status);
        }
        int n = jdbc.update("""
                update opportunity_tasks set status = :s, completed_at = case when :s = 'DONE' then :now else null end
                where id = :id and tenant_id = :t""", params(actor).addValue("id", taskId).addValue("s", status).addValue("now", now()));
        if (n == 0) {
            throw new NotFoundException("Task", taskId);
        }
        return tasks(actor.tenantId(), null, taskId).getFirst();
    }

    @Transactional
    public DecisionDto decide(AuthenticatedUser actor, UUID opportunityId, String decision, String rationale) {
        if (!Set.of("GO", "NO_GO").contains(decision)) {
            throw new BusinessRuleException("Decision must be GO or NO_GO");
        }
        UUID itemId = requireItem(actor.tenantId(), opportunityId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into bid_decisions (id, tenant_id, tenant_opportunity_id, decision, rationale, decided_by, decided_at)
                values (:id, :t, :i, :d, :r, :u, :now)""",
                params(actor).addValue("id", id).addValue("i", itemId).addValue("d", decision).addValue("r", rationale)
                        .addValue("now", now()));
        history(actor, itemId, "DECISION", null, decision, rationale);
        changeStage(actor, opportunityId, "GO".equals(decision) ? Stage.DOCUMENTS_BEING_PREPARED : Stage.WITHDRAWN,
                "Go/no-go decision: " + decision);
        return decisions(itemId).stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    /** Early-stage items whose opportunity has closed move to EXPIRED. */
    @Transactional
    public int expireClosed() {
        List<UUID> ids = jdbc.queryForList("""
                select t.id from tenant_opportunities t join opportunities o on o.id = t.opportunity_id
                where t.stage in ('DISCOVERED', 'UNDER_REVIEW', 'SHORTLISTED', 'GO_NO_GO')
                  and (o.status in ('CLOSED', 'EXPIRED', 'CANCELLED', 'AWARDED'))""", Map.of(), UUID.class);
        for (UUID id : ids) {
            jdbc.update("update tenant_opportunities set stage = 'EXPIRED', updated_at = :now, version = version + 1 where id = :id",
                    new MapSqlParameterSource("id", id).addValue("now", now()));
            jdbc.update("""
                    insert into bid_pipeline_history (id, tenant_id, tenant_opportunity_id, event_type, to_value, comment, occurred_at)
                    select :hid, tenant_id, id, 'STAGE_CHANGED', 'EXPIRED', 'The opportunity closed', :now from tenant_opportunities where id = :id""",
                    new MapSqlParameterSource("hid", UUID.randomUUID()).addValue("id", id).addValue("now", now()));
        }
        return ids.size();
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<PipelineItemDto> board(UUID tenantId) {
        return items(tenantId, null);
    }

    @Transactional(readOnly = true)
    public PipelineItemDto item(UUID tenantId, UUID opportunityId) {
        return items(tenantId, opportunityId).stream().findFirst().orElseThrow(() -> new NotFoundException("Pipeline item", opportunityId));
    }

    @Transactional(readOnly = true)
    public PipelineDetailDto detail(UUID tenantId, UUID opportunityId) {
        UUID itemId = requireItem(tenantId, opportunityId);
        return new PipelineDetailDto(item(tenantId, opportunityId), notes(tenantId, itemId), tasks(tenantId, itemId, null),
                decisions(itemId), history(itemId));
    }

    /** Open tasks across the tenant, optionally only those assigned to a user. */
    @Transactional(readOnly = true)
    public List<TaskDto> openTasks(UUID tenantId, UUID assigneeId) {
        return taskQuery("t.tenant_id = :t and t.status = 'OPEN'" + (assigneeId == null ? "" : " and t.assignee_id = :a"),
                new MapSqlParameterSource("t", tenantId).addValue("a", assigneeId));
    }

    private List<PipelineItemDto> items(UUID tenantId, UUID opportunityId) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", tenantId).addValue("o", opportunityId);
        List<Object[]> rows = jdbc.query("""
                select t.id, t.opportunity_id, t.stage, t.priority, t.assigned_to, u.full_name, t.submitted_at, t.bid_value,
                       t.outcome_notes, (select count(*) from opportunity_tasks k where k.tenant_opportunity_id = t.id and k.status = 'OPEN'),
                       m.score, t.updated_at
                from tenant_opportunities t
                left join users u on u.id = t.assigned_to
                left join opportunity_matches m on m.tenant_id = t.tenant_id and m.opportunity_id = t.opportunity_id
                where t.tenant_id = :t""" + (opportunityId == null ? "" : " and t.opportunity_id = :o") + " order by t.updated_at desc",
                p, (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getObject(5, UUID.class), rs.getString(6), inst(rs.getTimestamp(7)), rs.getBigDecimal(8), rs.getString(9),
                        rs.getInt(10), (Integer) rs.getObject(11), inst(rs.getTimestamp(12))});
        Map<UUID, OpportunitySummaryDto> summaries = opportunities.summaries(rows.stream().map(r -> (UUID) r[1]).toList()).stream()
                .collect(Collectors.toMap(OpportunitySummaryDto::id, Function.identity()));
        return rows.stream().map(r -> new PipelineItemDto((UUID) r[0], (UUID) r[1], Stage.valueOf((String) r[2]),
                Priority.valueOf((String) r[3]), (UUID) r[4], (String) r[5], (Instant) r[6], (BigDecimal) r[7], (String) r[8],
                (Integer) r[9], (Integer) r[10], (Instant) r[11], summaries.get((UUID) r[1]))).toList();
    }

    private List<NoteDto> notes(UUID tenantId, UUID itemId) {
        return jdbc.query("""
                select n.id, n.body, n.author_id, u.full_name, n.created_at from opportunity_notes n join users u on u.id = n.author_id
                where n.tenant_id = :t and n.tenant_opportunity_id = :i order by n.created_at desc""",
                new MapSqlParameterSource("t", tenantId).addValue("i", itemId),
                (rs, i) -> new NoteDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                        inst(rs.getTimestamp(5))));
    }

    private List<TaskDto> tasks(UUID tenantId, UUID itemId, UUID taskId) {
        return taskQuery("t.tenant_id = :t" + (itemId == null ? "" : " and t.tenant_opportunity_id = :i")
                        + (taskId == null ? "" : " and t.id = :k"),
                new MapSqlParameterSource("t", tenantId).addValue("i", itemId).addValue("k", taskId));
    }

    private List<TaskDto> taskQuery(String where, MapSqlParameterSource p) {
        return jdbc.query("""
                select t.id, x.opportunity_id, o.title, t.title, t.due_at, t.assignee_id, u.full_name, t.status, t.remind_at, t.created_at, t.completed_at
                from opportunity_tasks t join tenant_opportunities x on x.id = t.tenant_opportunity_id
                join opportunities o on o.id = x.opportunity_id left join users u on u.id = t.assignee_id
                """ + " where " + where + " order by t.status, t.due_at asc nulls last, t.created_at", p,
                (rs, i) -> new TaskDto(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        inst(rs.getTimestamp(5)), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), inst(rs.getTimestamp(9)),
                        inst(rs.getTimestamp(10)), inst(rs.getTimestamp(11))));
    }

    private List<DecisionDto> decisions(UUID itemId) {
        return jdbc.query("""
                select d.id, d.decision, d.rationale, d.decided_by, u.full_name, d.decided_at from bid_decisions d
                join users u on u.id = d.decided_by where d.tenant_opportunity_id = :i order by d.decided_at desc""",
                new MapSqlParameterSource("i", itemId),
                (rs, i) -> new DecisionDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getString(5), inst(rs.getTimestamp(6))));
    }

    private List<HistoryDto> history(UUID itemId) {
        return jdbc.query("""
                select h.event_type, h.from_value, h.to_value, h.comment, u.full_name, h.occurred_at from bid_pipeline_history h
                left join users u on u.id = h.actor_user_id where h.tenant_opportunity_id = :i order by h.occurred_at desc""",
                new MapSqlParameterSource("i", itemId),
                (rs, i) -> new HistoryDto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        inst(rs.getTimestamp(6))));
    }

    // ------------------------------------------------------------------ helpers

    private void history(AuthenticatedUser actor, UUID itemId, String type, String from, String to, String comment) {
        jdbc.update("""
                insert into bid_pipeline_history (id, tenant_id, tenant_opportunity_id, event_type, from_value, to_value, comment, actor_user_id, occurred_at)
                values (:id, :t, :i, :type, :from, :to, :c, :u, :now)""",
                params(actor).addValue("id", UUID.randomUUID()).addValue("i", itemId).addValue("type", type)
                        .addValue("from", from).addValue("to", to).addValue("c", comment).addValue("now", now()));
    }

    private void touch(UUID itemId) {
        jdbc.update("update tenant_opportunities set updated_at = :now where id = :id", new MapSqlParameterSource("id", itemId).addValue("now", now()));
    }

    private UUID findItemId(UUID tenantId, UUID opportunityId) {
        List<UUID> ids = jdbc.queryForList("select id from tenant_opportunities where tenant_id = :t and opportunity_id = :o",
                new MapSqlParameterSource("t", tenantId).addValue("o", opportunityId), UUID.class);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private UUID requireItem(UUID tenantId, UUID opportunityId) {
        UUID id = findItemId(tenantId, opportunityId);
        if (id == null) {
            throw new NotFoundException("Pipeline item for opportunity", opportunityId);
        }
        return id;
    }

    private void requireOpportunity(UUID opportunityId) {
        Integer n = jdbc.queryForObject("select count(*) from opportunities where id = :o", Map.of("o", opportunityId), Integer.class);
        if (n == null || n == 0) {
            throw new NotFoundException("Opportunity", opportunityId);
        }
    }

    private boolean isMember(UUID tenantId, UUID userId) {
        Integer n = jdbc.queryForObject("select count(*) from tenant_users where tenant_id = :t and user_id = :u and status = 'ACTIVE'",
                new MapSqlParameterSource("t", tenantId).addValue("u", userId), Integer.class);
        return n != null && n > 0;
    }

    private String userName(UUID userId) {
        List<String> n = jdbc.queryForList("select full_name from users where id = :u", Map.of("u", userId), String.class);
        return n.isEmpty() ? userId.toString() : n.getFirst();
    }

    private MapSqlParameterSource params(AuthenticatedUser actor) {
        return new MapSqlParameterSource("t", actor.tenantId()).addValue("u", actor.userId());
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant inst(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
