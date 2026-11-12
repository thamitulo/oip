package za.co.oip.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.common.error.Exceptions.ForbiddenException;
import za.co.oip.companies.CompanyProfileService;
import za.co.oip.documents.DocumentController;
import za.co.oip.documents.SourceDocumentRepository;
import za.co.oip.matching.MatchingService;
import za.co.oip.opportunities.OpportunityQueryService;
import za.co.oip.opportunities.OpportunityQueryService.Filter;
import za.co.oip.opportunities.OpportunityQueryService.SortOrder;
import za.co.oip.opportunities.api.OpportunityDtos.OpportunitySummaryDto;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod;
import za.co.oip.pipeline.PipelineService;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.security.AuthenticatedUser;
import za.co.oip.security.Permission;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tools available to the assistant. Every tool runs as the signed-in user and is scoped to their tenant; the
 * model never chooses a tenant. Results are compact JSON so the model reads only what it needs.
 */
@Component
public class AssistantTools {

    static final int MAX_RESULT_CHARS = 24_000;

    private final OpportunityQueryService opportunities;
    private final MatchingService matching;
    private final CompanyProfileService profiles;
    private final PipelineService pipeline;
    private final DocumentController documents;
    private final SourceDocumentRepository sourceDocuments;
    private final JsonMapper json;
    private final Clock clock;

    public AssistantTools(OpportunityQueryService opportunities, MatchingService matching, CompanyProfileService profiles,
                          PipelineService pipeline, DocumentController documents, SourceDocumentRepository sourceDocuments,
                          JsonMapper json, Clock clock) {
        this.opportunities = opportunities;
        this.matching = matching;
        this.profiles = profiles;
        this.pipeline = pipeline;
        this.documents = documents;
        this.sourceDocuments = sourceDocuments;
        this.json = json;
        this.clock = clock;
    }

    /** Tool definitions, in a fixed order so the request prefix stays cacheable. */
    public List<Tool> definitions() {
        return List.of(
                tool("search_opportunities",
                        "Search active procurement opportunities (tenders, RFQs, RFPs, EOIs). Returns up to `limit` summaries with id, "
                                + "title, reference, organisation, closing date/time (SAST), submission method and urgency. Use specific "
                                + "keywords (e.g. 'security services', 'water treatment'), optionally with filters.",
                        Map.of("query", prop("string", "Keywords; may be empty when filters are given"),
                                "sector", enumProp("Restrict to GOVERNMENT or MINING", "GOVERNMENT", "MINING"),
                                "event_type", enumProp("Type of procurement event", "TENDER", "RFQ", "RFP", "RFI", "EOI"),
                                "province", enumProp("South African province", "EASTERN_CAPE", "FREE_STATE", "GAUTENG", "KWAZULU_NATAL",
                                        "LIMPOPO", "MPUMALANGA", "NORTH_WEST", "NORTHERN_CAPE", "WESTERN_CAPE"),
                                "submission_method", enumProp("How bids are submitted", "ELECTRONIC", "PHYSICAL"),
                                "closing_within_days", prop("integer", "Only opportunities closing within this many days"),
                                "limit", prop("integer", "Maximum results (1-10, default 8)")),
                        List.of()),
                tool("get_opportunity",
                        "Full details of one opportunity: description, closing date/time, submission method and address, briefing, "
                                + "contacts, special conditions, documents (with stored-document ids for get_document_text) and "
                                + "requirements, each with provenance (SOURCE, DOCUMENT, AI).",
                        Map.of("opportunity_id", prop("string", "Opportunity UUID")), List.of("opportunity_id")),
                tool("get_document_text",
                        "Text extracted from one of an opportunity's documents (use the storedDocumentId from get_opportunity). "
                                + "Use it to answer questions about scope, requirements or evaluation criteria.",
                        Map.of("document_id", prop("string", "Stored document UUID"),
                                "max_chars", prop("integer", "Maximum characters to return (default 15000, max 20000)")),
                        List.of("document_id")),
                tool("get_company_profile",
                        "The user's company profile: services, products, industries, regions, certifications, registrations, "
                                + "capabilities and B-BBEE/CIDB details. All claims are self-declared unless marked VERIFIED.",
                        Map.of(), List.of()),
                tool("get_match",
                        "The platform's explainable match of an opportunity against the company profile: score 0-100, label, "
                                + "what matched, what is missing or unknown, and disqualifiers (missing mandatory requirements).",
                        Map.of("opportunity_id", prop("string", "Opportunity UUID")), List.of("opportunity_id")),
                tool("list_recommended",
                        "Active opportunities that best match the company profile, highest score first.",
                        Map.of("min_score", prop("integer", "Minimum score 0-100 (default 40)"),
                                "limit", prop("integer", "Maximum results (1-10, default 8)")),
                        List.of()),
                tool("get_pipeline",
                        "The company's bid pipeline: opportunities being tracked with stage, assignee, open tasks and closing dates.",
                        Map.of(), List.of()),
                tool("shortlist_opportunity",
                        "Add an opportunity to the company's bid pipeline as SHORTLISTED. Only call this when the user explicitly "
                                + "asks you to shortlist or track an opportunity.",
                        Map.of("opportunity_id", prop("string", "Opportunity UUID"),
                                "note", prop("string", "Optional reason, recorded in the pipeline history")),
                        List.of("opportunity_id")));
    }

    /** Runs a tool and returns its JSON result. Errors are returned as messages the model can act on. */
    public String execute(AuthenticatedUser user, String name, Map<String, Object> input) {
        Object result = switch (name) {
            case "search_opportunities" -> search(input);
            case "get_opportunity" -> opportunity(uuid(input, "opportunity_id"));
            case "get_document_text" -> documentText(uuid(input, "document_id"), integer(input, "max_chars", 15000, 1000, 20000));
            case "get_company_profile" -> profiles.get(user.tenantId());
            case "get_match" -> matching.match(user.tenantId(), uuid(input, "opportunity_id"));
            case "list_recommended" -> recommended(user, input);
            case "get_pipeline" -> pipeline.board(user.tenantId()).stream().map(i -> Map.of(
                    "opportunityId", i.opportunityId(), "title", i.opportunity() == null ? "" : i.opportunity().title(),
                    "stage", i.stage(), "assignedTo", String.valueOf(i.assignedToName()), "openTasks", i.openTasks(),
                    "closingDate", String.valueOf(i.opportunity() == null ? null : i.opportunity().closingDate()),
                    "matchScore", String.valueOf(i.matchScore()))).toList();
            case "shortlist_opportunity" -> {
                if (!user.authorities().stream().anyMatch(a -> a.getAuthority().equals(Permission.PIPELINE_EDIT.name()))) {
                    throw new ForbiddenException("Your role cannot change the pipeline");
                }
                yield pipeline.shortlist(user, uuid(input, "opportunity_id"),
                        "Added via assistant" + (input.get("note") == null ? "" : ": " + input.get("note")));
            }
            default -> Map.of("error", "Unknown tool " + name);
        };
        String out = json.writeValueAsString(result);
        return out.length() > MAX_RESULT_CHARS ? out.substring(0, MAX_RESULT_CHARS) + "…[truncated]" : out;
    }

    private List<Map<String, Object>> search(Map<String, Object> in) {
        Integer days = in.get("closing_within_days") == null ? null : integer(in, "closing_within_days", 30, 0, 365);
        LocalDate today = LocalDate.now(clock.withZone(za.co.oip.crawling.adapter.ParsingSupport.SOUTH_AFRICA));
        String q = in.get("query") == null ? null : in.get("query").toString();
        Filter f = new Filter(q, null, enumValue(Sector.class, in.get("sector")), enumValue(EventType.class, in.get("event_type")),
                null, in.get("province") == null ? null : in.get("province").toString(), null, null, true,
                null, days == null ? null : today.plusDays(days), null, null, null, null,
                q == null || q.isBlank() ? SortOrder.CLOSING_SOONEST : SortOrder.RELEVANCE,
                enumValue(SubmissionMethod.class, in.get("submission_method")), null);
        return opportunities.search(f, 0, integer(in, "limit", 8, 1, 10)).content().stream().map(AssistantTools::summary).toList();
    }

    private Map<String, Object> opportunity(UUID id) {
        var d = opportunities.get(id);
        Map<String, Object> m = new LinkedHashMap<>(summary(d.summary()));
        m.put("description", d.description());
        m.put("sourceCategory", d.sourceCategoryLabel());
        m.put("submission", d.submission());
        m.put("briefing", d.briefingDetails());
        m.put("briefingCompulsory", d.briefingCompulsory());
        m.put("specialConditions", d.specialConditions());
        m.put("estimatedValue", d.estimatedValue());
        m.put("contact", Map.of("name", String.valueOf(d.contactName()), "email", String.valueOf(d.contactEmail()),
                "phone", String.valueOf(d.contactPhone())));
        m.put("documents", opportunities.documents(id).stream().map(doc -> Map.of("title", String.valueOf(doc.title()),
                "url", doc.url(), "type", doc.documentType(), "storedDocumentId", String.valueOf(doc.storedDocumentId()),
                "processingStatus", doc.processingStatus())).toList());
        m.put("requirements", documents.requirements(id));
        m.put("sourceEvidence", Map.of("source", d.evidence().sourceName(), "firstDiscovered", d.evidence().firstDiscoveredAt().toString(),
                "lastChecked", d.evidence().lastCheckedAt().toString()));
        return m;
    }

    private Map<String, Object> documentText(UUID id, int maxChars) {
        var sd = sourceDocuments.findById(id).orElse(null);
        if (sd == null || sd.getTextContent() == null) {
            return Map.of("error", "No extracted text for this document (it may not have been processed yet)");
        }
        String text = sd.getTextContent();
        return Map.of("documentId", id, "pages", String.valueOf(sd.getPageCount()), "truncated", text.length() > maxChars,
                "text", text.length() > maxChars ? text.substring(0, maxChars) : text);
    }

    private List<Map<String, Object>> recommended(AuthenticatedUser user, Map<String, Object> in) {
        matching.refresh(user.tenantId(), 5000);
        var recs = matching.recommended(user.tenantId(), integer(in, "min_score", 40, 0, 100), integer(in, "limit", 8, 1, 10), 0);
        var summaries = opportunities.summaries(recs.stream().map(MatchingService.Recommended::opportunityId).toList());
        return summaries.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>(summary(s));
            recs.stream().filter(r -> r.opportunityId().equals(s.id())).findFirst().ifPresent(r -> {
                m.put("matchScore", r.score());
                m.put("matchLabel", r.label());
                m.put("disqualifiers", r.disqualifierCount());
            });
            return m;
        }).toList();
    }

    static Map<String, Object> summary(OpportunitySummaryDto s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("title", s.title());
        m.put("reference", s.referenceNumber());
        m.put("organisation", s.organisationName());
        m.put("type", s.eventType());
        m.put("sector", s.sector());
        m.put("province", s.province());
        m.put("closingDate", s.closingDate());
        m.put("closingAt", s.closingAt());
        m.put("closingTimePublished", s.closingTimeSpecified());
        m.put("daysUntilClosing", s.daysUntilClosing());
        m.put("submissionMethod", s.submissionMethod());
        m.put("compulsoryBriefing", s.briefingCompulsory());
        m.put("link", "/opportunities/" + s.id());
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private static Tool tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(required).build())
                .build();
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> enumProp(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }

    private static UUID uuid(Map<String, Object> in, String key) {
        Object v = in.get(key);
        if (v == null) throw new IllegalArgumentException(key + " is required");
        try {
            return UUID.fromString(v.toString().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + " must be a UUID");
        }
    }

    private static int integer(Map<String, Object> in, String key, int def, int min, int max) {
        Object v = in.get(key);
        int n;
        try {
            n = v == null ? def : (v instanceof Number num ? num.intValue() : Integer.parseInt(v.toString().trim()));
        } catch (NumberFormatException e) {
            n = def;
        }
        return Math.max(min, Math.min(max, n));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object v) {
        if (v == null || v.toString().isBlank()) return null;
        try {
            return Enum.valueOf(type, v.toString().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
