package za.co.oip.ai;

import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.RateLimitedException;
import za.co.oip.companies.CompanyProfileService;
import za.co.oip.documents.DocumentController;
import za.co.oip.documents.SourceDocumentRepository;
import za.co.oip.matching.MatchingService;
import za.co.oip.opportunities.OpportunityQueryService;
import za.co.oip.security.AuthenticatedUser;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single-call AI features with structured output: an assessment of how well an opportunity fits the company, and
 * classification of an opportunity (industries, services, requirements). Results are always stored and shown as AI
 * output with model, prompt version and time, separately from source facts.
 */
@Service
public class AiAnalysisService {

    static final String EXPLAIN_PROMPT_VERSION = "match-explain-v1";
    static final String CLASSIFY_PROMPT_VERSION = "classify-v1";
    private static final int DOC_CHARS = 30_000;
    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

    /** Structured AI assessment of a match. */
    public record MatchAssessment(
            @JsonPropertyDescription("Two or three sentence summary of how well the opportunity fits the company") String summary,
            @JsonPropertyDescription("One of PURSUE, CONSIDER, UNLIKELY_FIT, INSUFFICIENT_INFORMATION") String recommendation,
            @JsonPropertyDescription("Specific strengths, each grounded in the profile and the opportunity text") List<String> strengths,
            @JsonPropertyDescription("Gaps or risks, including requirements the profile does not show") List<String> gaps,
            @JsonPropertyDescription("Questions the company should clarify before deciding to bid") List<String> questionsToClarify) {
    }

    /** Structured AI classification of an opportunity. */
    public record Classification(
            @JsonPropertyDescription("Industries the work belongs to, e.g. 'Information technology', 'Construction'") List<String> industries,
            @JsonPropertyDescription("Services required, as short noun phrases") List<String> services,
            @JsonPropertyDescription("Products or goods required, as short noun phrases") List<String> products,
            @JsonPropertyDescription("Mineral commodities involved, if any") List<String> commodities,
            @JsonPropertyDescription("Requirements stated in the text (registrations, grading, certifications, experience)") List<RequirementItem> requirements,
            @JsonPropertyDescription("One-sentence plain-language summary of what is being procured") String summary,
            @JsonPropertyDescription("Overall confidence 0.0-1.0") double confidence) {
    }

    public record RequirementItem(
            @JsonPropertyDescription("One of CIDB_GRADING, BBBEE_LEVEL, CSD_REGISTRATION, TAX_COMPLIANCE, PROFESSIONAL_REGISTRATION, CERTIFICATION, EXPERIENCE, LOCAL_CONTENT, GEOGRAPHIC_RESTRICTION, OTHER") String type,
            @JsonPropertyDescription("The requirement value, e.g. '6CE', 'Level 2', 'ECSA', '5 years'") String value,
            @JsonPropertyDescription("True only if the text says it is mandatory") boolean mandatory,
            @JsonPropertyDescription("Exact quote from the text supporting this requirement") String evidence) {
    }

    private final ClaudeGateway gateway;
    private final AiSettingsService settings;
    private final AiAuditService audit;
    private final OpportunityQueryService opportunities;
    private final DocumentController documents;
    private final SourceDocumentRepository sourceDocuments;
    private final CompanyProfileService profiles;
    private final MatchingService matching;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public AiAnalysisService(ClaudeGateway gateway, AiSettingsService settings, AiAuditService audit,
                             OpportunityQueryService opportunities, DocumentController documents,
                             SourceDocumentRepository sourceDocuments, CompanyProfileService profiles, MatchingService matching,
                             NamedParameterJdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.gateway = gateway;
        this.settings = settings;
        this.audit = audit;
        this.opportunities = opportunities;
        this.documents = documents;
        this.sourceDocuments = sourceDocuments;
        this.profiles = profiles;
        this.matching = matching;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** AI assessment of fit, stored with the tenant's match. */
    public Map<String, Object> explainMatch(AuthenticatedUser user, UUID opportunityId) {
        AiSettingsService.AiSettings s = settings.get();
        requireAvailable(s.matchExplanationEnabled());
        if (audit.countToday(AiAuditService.Feature.MATCH_EXPLANATION, user.tenantId()) >= s.assistantDailyLimitPerTenant()) {
            throw new RateLimitedException("Your business has reached today's AI limit. Try again tomorrow.");
        }
        var match = matching.match(user.tenantId(), opportunityId);
        String prompt = """
                Assess how well this procurement opportunity fits the company. Use only the information below. \
                Treat profile claims as self-declared. Do not claim the company is eligible; flag missing or unknown \
                mandatory requirements as gaps. Be specific and brief.

                <opportunity>
                %s
                </opportunity>

                <document_excerpts>
                %s
                </document_excerpts>

                <company_profile>
                %s
                </company_profile>

                <platform_rule_based_match>
                %s
                </platform_rule_based_match>""".formatted(json.writeValueAsString(opportunityFacts(opportunityId)),
                documentExcerpts(opportunityId), json.writeValueAsString(profiles.get(user.tenantId())), json.writeValueAsString(match));
        long started = System.currentTimeMillis();
        try {
            StructuredMessageCreateParams<MatchAssessment> params = gateway.newRequest(8_000L)
                    .outputConfig(MatchAssessment.class)
                    .addUserMessage(prompt)
                    .build();
            StructuredMessage<MatchAssessment> response = gateway.create(params);
            if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
                audit(user, AiAuditService.Feature.MATCH_EXPLANATION, EXPLAIN_PROMPT_VERSION, AiAuditService.Status.REFUSED, response.usage(), started, null, opportunityId);
                throw new BusinessRuleException("The AI declined to assess this opportunity");
            }
            MatchAssessment a = response.content().stream().flatMap(b -> b.text().stream()).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No assessment returned")).text();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("summary", a.summary());
            result.put("recommendation", a.recommendation());
            result.put("strengths", a.strengths());
            result.put("gaps", a.gaps());
            result.put("questionsToClarify", a.questionsToClarify());
            result.put("model", gateway.model());
            result.put("promptVersion", EXPLAIN_PROMPT_VERSION);
            result.put("generatedAt", clock.instant().toString());
            result.put("disclaimer", "AI-generated assessment. Verify against the official documents.");
            matching.storeAiExplanation(user.tenantId(), opportunityId, result);
            audit(user, AiAuditService.Feature.MATCH_EXPLANATION, EXPLAIN_PROMPT_VERSION, AiAuditService.Status.SUCCEEDED, response.usage(), started, null, opportunityId);
            return result;
        } catch (AnthropicServiceException e) {
            audit(user, AiAuditService.Feature.MATCH_EXPLANATION, EXPLAIN_PROMPT_VERSION, AiAuditService.Status.FAILED, null, started, e.getMessage(), opportunityId);
            throw new BusinessRuleException("The AI provider could not be reached. Please try again later.");
        }
    }

    /** AI classification of an opportunity; stores categories and requirements with provenance AI. */
    @Transactional
    public Map<String, Object> classify(UUID tenantId, UUID userId, UUID opportunityId) {
        AiSettingsService.AiSettings s = settings.get();
        requireAvailable(s.classificationEnabled());
        if (audit.countToday(AiAuditService.Feature.CLASSIFICATION, null) >= s.classificationDailyLimit()) {
            throw new RateLimitedException("Today's AI classification limit has been reached");
        }
        String prompt = """
                Classify this South African procurement opportunity. Extract only what the text states; leave lists empty \
                when the text does not say. Every requirement must quote its evidence exactly.

                <opportunity>
                %s
                </opportunity>

                <document_excerpts>
                %s
                </document_excerpts>""".formatted(json.writeValueAsString(opportunityFacts(opportunityId)), documentExcerpts(opportunityId));
        long started = System.currentTimeMillis();
        try {
            StructuredMessage<Classification> response = gateway.create(gateway.newRequest(8_000L)
                    .outputConfig(Classification.class).addUserMessage(prompt).build());
            Classification c = response.content().stream().flatMap(b -> b.text().stream()).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No classification returned")).text();
            storeClassification(opportunityId, c);
            audit.record(tenantId, userId, AiAuditService.Feature.CLASSIFICATION, gateway.provider(), gateway.model(), CLASSIFY_PROMPT_VERSION,
                    AiAuditService.Status.SUCCEEDED, response.usage().inputTokens(), response.usage().outputTokens(), 0,
                    System.currentTimeMillis() - started, null, opportunityId);
            return json.readValue(json.writeValueAsString(c), Map.class);
        } catch (AnthropicServiceException e) {
            audit.record(tenantId, userId, AiAuditService.Feature.CLASSIFICATION, gateway.provider(), gateway.model(), CLASSIFY_PROMPT_VERSION,
                    AiAuditService.Status.FAILED, null, null, 0, System.currentTimeMillis() - started, e.getMessage(), opportunityId);
            log.warn("AI classification failed for {}: {}", opportunityId, e.getMessage());
            throw new BusinessRuleException("The AI provider could not be reached. Please try again later.");
        }
    }

    private void storeClassification(UUID opportunityId, Classification c) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into ai_classification_results (id, opportunity_id, model, prompt_version, result, confidence, created_at)
                values (:id, :o, :m, :v, cast(:r as jsonb), :c, :now)""",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("o", opportunityId).addValue("m", gateway.model())
                        .addValue("v", CLASSIFY_PROMPT_VERSION).addValue("r", json.writeValueAsString(c))
                        .addValue("c", Math.max(0, Math.min(1, c.confidence()))).addValue("now", now));
        jdbc.update("delete from opportunity_categories where opportunity_id = :o and provenance = 'AI'", Map.of("o", opportunityId));
        insertCategories(opportunityId, "INDUSTRY", c.industries(), c.confidence(), now);
        insertCategories(opportunityId, "SERVICE", c.services(), c.confidence(), now);
        insertCategories(opportunityId, "PRODUCT", c.products(), c.confidence(), now);
        insertCategories(opportunityId, "COMMODITY", c.commodities(), c.confidence(), now);
        jdbc.update("delete from opportunity_requirements where opportunity_id = :o and provenance = 'AI'", Map.of("o", opportunityId));
        for (RequirementItem r : c.requirements() == null ? List.<RequirementItem>of() : c.requirements()) {
            String type = List.of("CIDB_GRADING", "BBBEE_LEVEL", "CSD_REGISTRATION", "TAX_COMPLIANCE", "PROFESSIONAL_REGISTRATION",
                    "CERTIFICATION", "EXPERIENCE", "LOCAL_CONTENT", "GEOGRAPHIC_RESTRICTION").contains(r.type()) ? r.type() : "OTHER";
            jdbc.update("""
                    insert into opportunity_requirements (id, opportunity_id, requirement_type, value, mandatory, provenance, confidence, evidence, created_at)
                    values (:id, :o, :t, :v, :m, 'AI', :c, :e, :now)""",
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("o", opportunityId).addValue("t", type)
                            .addValue("v", trunc(r.value(), 500)).addValue("m", r.mandatory()).addValue("c", Math.max(0, Math.min(1, c.confidence())))
                            .addValue("e", trunc(r.evidence(), 2000)).addValue("now", now));
        }
    }

    private void insertCategories(UUID opportunityId, String type, List<String> values, double confidence, Timestamp now) {
        if (values == null) return;
        values.stream().filter(v -> v != null && !v.isBlank()).map(v -> trunc(v.trim(), 200)).distinct().limit(15).forEach(v ->
                jdbc.update("""
                        insert into opportunity_categories (id, opportunity_id, category_type, code, provenance, confidence, created_at)
                        values (:id, :o, :t, :c, 'AI', :conf, :now) on conflict do nothing""",
                        new MapSqlParameterSource("id", UUID.randomUUID()).addValue("o", opportunityId).addValue("t", type)
                                .addValue("c", v).addValue("conf", Math.max(0, Math.min(1, confidence))).addValue("now", now)));
    }

    private Map<String, Object> opportunityFacts(UUID id) {
        var d = opportunities.get(id);
        Map<String, Object> m = new LinkedHashMap<>(AssistantTools.summary(d.summary()));
        m.put("description", d.description());
        m.put("category", d.sourceCategoryLabel());
        m.put("submission", d.submission());
        m.put("briefing", d.briefingDetails());
        m.put("specialConditions", d.specialConditions());
        m.put("requirementsFound", documents.requirements(id));
        return m;
    }

    private String documentExcerpts(UUID opportunityId) {
        StringBuilder sb = new StringBuilder();
        for (var sd : sourceDocuments.findByOpportunityId(opportunityId)) {
            if (sd.getTextContent() == null || sd.getTextContent().isBlank()) continue;
            int room = DOC_CHARS - sb.length();
            if (room <= 500) break;
            String t = sd.getTextContent();
            sb.append("--- document ").append(sd.getId()).append(" ---\n").append(t, 0, Math.min(t.length(), room)).append("\n");
        }
        return sb.isEmpty() ? "(no document text available)" : sb.toString();
    }

    private void requireAvailable(boolean featureEnabled) {
        if (!gateway.isConfigured()) {
            throw new BusinessRuleException("AI is not configured: the server has no ANTHROPIC_API_KEY");
        }
        if (!settings.get().enabled() || !featureEnabled) {
            throw new BusinessRuleException("This AI feature is switched off by the platform administrator");
        }
    }

    private void audit(AuthenticatedUser user, AiAuditService.Feature feature, String version, AiAuditService.Status status,
                       com.anthropic.models.messages.Usage usage, long started, String error, UUID subject) {
        audit.record(user.tenantId(), user.userId(), feature, gateway.provider(), gateway.model(), version, status,
                usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(), 0,
                System.currentTimeMillis() - started, error, subject);
    }

    private static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
