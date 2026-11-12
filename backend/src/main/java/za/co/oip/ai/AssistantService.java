package za.co.oip.ai;

import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.common.error.Exceptions.RateLimitedException;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.security.AuthenticatedUser;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The in-app assistant: a Claude tool-use loop over tenant-scoped platform tools. Conversations are stored per user;
 * earlier turns are replayed as text (tool traces are kept for audit, not replayed).
 */
@Service
public class AssistantService {

    static final String PROMPT_VERSION = "assistant-v1";
    private static final int MAX_TOOL_ROUNDS = 8;
    private static final int HISTORY_TURNS = 12;
    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    static final String SYSTEM_PROMPT = """
            You are the Opportunity Assistant in the Opportunity Intelligence Platform (OIP). You help a South African business \
            find, understand and decide on procurement opportunities (tenders, RFQs, RFPs, EOIs and supplier opportunities) \
            published by government entities and mining companies.

            How to work:
            - Use the tools to look things up. Base every statement about an opportunity, document, match or pipeline item on \
            tool results from this conversation. Never invent opportunities, reference numbers, dates, values, addresses or \
            requirements. If the tools don't have the answer, say so and point the user to the official documents.
            - Values carry provenance. SOURCE means the source published it as a field; SOURCE_POLICY means the source states it \
            for all its listings; DOCUMENT means the platform derived it from document text by rules; AI means a model inferred \
            it. Mention when something was derived or inferred rather than published.
            - Match scores are indicative only. Never say the company is eligible or will win. Always point out disqualifiers \
            (missing mandatory requirements) and unknowns. Company profile claims are self-declared unless marked VERIFIED.
            - Times are South African Standard Time (SAST). Give the closing time when it is published; if only a date is \
            published, say the time is not stated.
            - For submission, say whether bids are submitted electronically or physically, and give the delivery address or \
            channel when known.
            - When you mention an opportunity, give its title, reference number and closing date, and link it as \
            [title](/opportunities/<id>).
            - Only call shortlist_opportunity when the user explicitly asks you to add or track an opportunity.

            Style: answer first, then supporting detail. Be concise. Use short bullet lists when listing opportunities \
            (at most 10). Use plain Markdown; no tables wider than four columns.""";

    public record MessageDto(UUID id, String role, String content, Instant createdAt) {
    }

    public record ConversationDto(UUID id, String title, Instant createdAt, Instant updatedAt) {
    }

    public record ReplyDto(UUID conversationId, MessageDto message, List<String> toolsUsed) {
    }

    private final ClaudeGateway gateway;
    private final AssistantTools tools;
    private final AiSettingsService settings;
    private final AiAuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public AssistantService(ClaudeGateway gateway, AssistantTools tools, AiSettingsService settings, AiAuditService audit,
                            NamedParameterJdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.gateway = gateway;
        this.tools = tools;
        this.settings = settings;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public ReplyDto ask(AuthenticatedUser user, UUID conversationId, String question, String userContext) {
        AiSettingsService.AiSettings s = settings.get();
        if (!gateway.isConfigured()) {
            throw new BusinessRuleException("The assistant is not configured: the server has no ANTHROPIC_API_KEY");
        }
        if (!s.enabled() || !s.assistantEnabled()) {
            throw new BusinessRuleException("The assistant is switched off by the platform administrator");
        }
        if (audit.countToday(AiAuditService.Feature.ASSISTANT, user.tenantId()) >= s.assistantDailyLimitPerTenant()) {
            audit.record(user.tenantId(), user.userId(), AiAuditService.Feature.ASSISTANT, gateway.provider(), s.model(), PROMPT_VERSION,
                    AiAuditService.Status.LIMITED, null, null, 0, 0, "Daily limit reached", conversationId);
            throw new RateLimitedException("Your business has reached today's assistant limit. Try again tomorrow.");
        }
        UUID convId = conversationId == null ? createConversation(user, question) : requireConversation(user, conversationId);
        List<String[]> history = history(convId);
        saveMessage(user, convId, "USER", question, null);

        long started = System.currentTimeMillis();
        long inputTokens = 0;
        long outputTokens = 0;
        List<Map<String, Object>> trace = new ArrayList<>();
        String answer;
        AiAuditService.Status status = AiAuditService.Status.SUCCEEDED;
        String error = null;
        try {
            MessageCreateParams.Builder request = gateway.newRequest(16_000L)
                    .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(SYSTEM_PROMPT)
                            .cacheControl(CacheControlEphemeral.builder().build()).build()));
            for (Tool t : tools.definitions()) {
                request.addTool(t);
            }
            for (String[] turn : history) {
                if ("USER".equals(turn[0])) request.addUserMessage(turn[1]);
                else request.addAssistantMessage(turn[1]);
            }
            String today = LocalDate.now(clock.withZone(ParsingSupport.SOUTH_AFRICA)).toString();
            request.addUserMessage("[Context: today is " + today + " (Africa/Johannesburg). " + userContext + "]\n\n" + question);

            answer = null;
            for (int round = 0; round < MAX_TOOL_ROUNDS && answer == null; round++) {
                Message response = gateway.create(request.build());
                inputTokens += response.usage().inputTokens();
                outputTokens += response.usage().outputTokens();
                StopReason stop = response.stopReason().orElse(StopReason.END_TURN);
                if (StopReason.REFUSAL.equals(stop)) {
                    status = AiAuditService.Status.REFUSED;
                    answer = "I can't help with that request.";
                    break;
                }
                request.addMessage(response);
                List<ToolUseBlock> calls = response.content().stream().flatMap(b -> b.toolUse().stream()).toList();
                if (!StopReason.TOOL_USE.equals(stop) || calls.isEmpty()) {
                    answer = text(response);
                    if (StopReason.MAX_TOKENS.equals(stop)) {
                        answer += "\n\n_(The answer was cut short. Ask me to continue.)_";
                    }
                    break;
                }
                List<ContentBlockParam> results = new ArrayList<>();
                for (ToolUseBlock call : calls) {
                    results.add(ContentBlockParam.ofToolResult(runTool(user, call, trace)));
                }
                request.addUserMessageOfContentBlockParams(results);
            }
            if (answer == null) {
                answer = "I couldn't finish within the allowed number of lookups. Please narrow the question.";
            }
        } catch (RateLimitException e) {
            status = AiAuditService.Status.FAILED;
            error = "Provider rate limit";
            answer = "The AI provider is busy right now. Please try again in a minute.";
        } catch (AnthropicServiceException e) {
            status = AiAuditService.Status.FAILED;
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Assistant call failed: {}", error);
            answer = "The assistant could not reach the AI provider. Please try again later.";
        } catch (RuntimeException e) {
            status = AiAuditService.Status.FAILED;
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Assistant failed", e);
            answer = "Something went wrong while answering. Please try again.";
        }
        audit.record(user.tenantId(), user.userId(), AiAuditService.Feature.ASSISTANT, gateway.provider(), s.model(), PROMPT_VERSION,
                status, inputTokens, outputTokens, trace.size(), System.currentTimeMillis() - started, error, convId);
        MessageDto saved = saveMessage(user, convId, "ASSISTANT", answer, trace);
        return new ReplyDto(convId, saved, trace.stream().map(t -> (String) t.get("tool")).distinct().toList());
    }

    private ToolResultBlockParam runTool(AuthenticatedUser user, ToolUseBlock call, List<Map<String, Object>> trace) {
        Map<String, Object> input;
        try {
            input = call._input().convert(new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException e) {
            input = Map.of();
        }
        if (input == null) input = Map.of();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tool", call.name());
        entry.put("input", input);
        try {
            String result = tools.execute(user, call.name(), input);
            entry.put("ok", true);
            trace.add(entry);
            return ToolResultBlockParam.builder().toolUseId(call.id()).content(result).build();
        } catch (NotFoundException | IllegalArgumentException | BusinessRuleException e) {
            entry.put("ok", false);
            entry.put("error", e.getMessage());
            trace.add(entry);
            return ToolResultBlockParam.builder().toolUseId(call.id()).content("Error: " + e.getMessage()).isError(true).build();
        } catch (RuntimeException e) {
            entry.put("ok", false);
            entry.put("error", "failed");
            trace.add(entry);
            log.warn("Assistant tool {} failed", call.name(), e);
            return ToolResultBlockParam.builder().toolUseId(call.id()).content("Error: the tool failed").isError(true).build();
        }
    }

    private static String text(Message response) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : response.content()) {
            b.text().ifPresent(t -> {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(t.text());
            });
        }
        return sb.isEmpty() ? "(No answer was returned.)" : sb.toString().trim();
    }

    // ------------------------------------------------------------------ conversations

    public List<ConversationDto> conversations(AuthenticatedUser user) {
        return jdbc.query("""
                select id, title, created_at, updated_at from assistant_conversations where tenant_id = :t and user_id = :u
                order by updated_at desc limit 50""", params(user),
                (rs, i) -> new ConversationDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4).toInstant()));
    }

    public List<MessageDto> messages(AuthenticatedUser user, UUID conversationId) {
        requireConversation(user, conversationId);
        return jdbc.query("select id, role, content, created_at from assistant_messages where conversation_id = :c order by created_at",
                new MapSqlParameterSource("c", conversationId),
                (rs, i) -> new MessageDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()));
    }

    public void delete(AuthenticatedUser user, UUID conversationId) {
        requireConversation(user, conversationId);
        jdbc.update("delete from assistant_conversations where id = :c", new MapSqlParameterSource("c", conversationId));
    }

    private UUID createConversation(AuthenticatedUser user, String question) {
        UUID id = UUID.randomUUID();
        String title = question.length() > 80 ? question.substring(0, 77) + "…" : question;
        jdbc.update("""
                insert into assistant_conversations (id, tenant_id, user_id, title, created_at, updated_at)
                values (:id, :t, :u, :title, :now, :now)""",
                params(user).addValue("id", id).addValue("title", title).addValue("now", Timestamp.from(clock.instant())));
        return id;
    }

    private UUID requireConversation(AuthenticatedUser user, UUID id) {
        Integer n = jdbc.queryForObject("select count(*) from assistant_conversations where id = :id and tenant_id = :t and user_id = :u",
                params(user).addValue("id", id), Integer.class);
        if (n == null || n == 0) {
            throw new NotFoundException("Conversation", id);
        }
        return id;
    }

    private List<String[]> history(UUID conversationId) {
        List<String[]> rows = jdbc.query("""
                select role, content from assistant_messages where conversation_id = :c order by created_at desc limit :n""",
                new MapSqlParameterSource("c", conversationId).addValue("n", HISTORY_TURNS * 2),
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)});
        List<String[]> ordered = new ArrayList<>(rows.reversed());
        // The conversation must start with a user turn.
        while (!ordered.isEmpty() && !"USER".equals(ordered.getFirst()[0])) ordered.removeFirst();
        return ordered;
    }

    private MessageDto saveMessage(AuthenticatedUser user, UUID conversationId, String role, String content, List<Map<String, Object>> trace) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into assistant_messages (id, conversation_id, tenant_id, role, content, tool_trace, created_at)
                values (:id, :c, :t, :role, :content, cast(:trace as jsonb), :now)""",
                params(user).addValue("id", id).addValue("c", conversationId).addValue("role", role).addValue("content", content)
                        .addValue("trace", trace == null ? null : json.writeValueAsString(trace)).addValue("now", now));
        jdbc.update("update assistant_conversations set updated_at = :now where id = :c", new MapSqlParameterSource("c", conversationId).addValue("now", now));
        return new MessageDto(id, role, content, now.toInstant());
    }

    private static MapSqlParameterSource params(AuthenticatedUser user) {
        return new MapSqlParameterSource("t", user.tenantId()).addValue("u", user.userId());
    }
}
