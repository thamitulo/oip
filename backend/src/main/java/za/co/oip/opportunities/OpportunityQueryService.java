package za.co.oip.opportunities;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.common.api.PageResponse;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.opportunities.api.OpportunityDtos.CategoryDto;
import za.co.oip.opportunities.api.OpportunityDtos.ChangeEventDto;
import za.co.oip.opportunities.api.OpportunityDtos.DocumentDto;
import za.co.oip.opportunities.api.OpportunityDtos.EvidenceDto;
import za.co.oip.opportunities.api.OpportunityDtos.OpportunityDetailDto;
import za.co.oip.opportunities.api.OpportunityDtos.OpportunitySummaryDto;
import za.co.oip.opportunities.domain.Opportunity;
import za.co.oip.opportunities.domain.OpportunityCategoryRepository;
import za.co.oip.opportunities.domain.OpportunityDocumentRepository;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;
import za.co.oip.opportunities.domain.OpportunityEnums.ProcurementCategory;
import za.co.oip.opportunities.domain.OpportunityRepository;
import za.co.oip.opportunities.domain.SourceChangeEventRepository;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.sources.domain.Source;
import za.co.oip.sources.domain.SourceRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read model for the opportunity catalogue. Built with parameterised SQL because search needs PostgreSQL
 * full-text ranking and joins across sources and organisations; all user input is bound as parameters.
 */
@Service
public class OpportunityQueryService {

    /** Integration statuses whose access has been established and verified by an administrator. */
    private static final Set<String> VERIFIED_SOURCE_STATUSES = Set.of("ACTIVE_PUBLIC", "ACTIVE_API", "AUTHORISED_INTEGRATION");

    private static final String SELECT = """
            select o.id, o.title, o.reference_number, o.event_type, o.procurement_category, o.status, o.status_inferred,
                   o.organisation_id, org.name as organisation_name,
                   coalesce(case when org.sector = 'MINING' or s.sector = 'MINING' then 'MINING' end, s.sector) as sector,
                   o.province, o.locality, o.closing_date, o.closing_at, o.closing_time_specified, o.published_at,
                   o.first_discovered_at, o.source_id, s.name as source_name, s.integration_status,
                   s.last_verified_at, o.source_url,
                   (select count(*) from opportunity_documents d where d.opportunity_id = o.id and d.removed_at is null) as document_count,
                   o.submission_method, o.mining_related, o.briefing_compulsory
            """;
    private static final String FROM = """
            from opportunities o
            join source_registry s on s.id = o.source_id
            left join procurement_organisations org on org.id = o.organisation_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final OpportunityRepository opportunityRepository;
    private final OpportunityDocumentRepository documentRepository;
    private final OpportunityCategoryRepository categoryRepository;
    private final SourceChangeEventRepository changeEventRepository;
    private final SourceRepository sourceRepository;
    private final Clock clock;

    public OpportunityQueryService(NamedParameterJdbcTemplate jdbc, OpportunityRepository opportunityRepository,
                                   OpportunityDocumentRepository documentRepository,
                                   OpportunityCategoryRepository categoryRepository,
                                   SourceChangeEventRepository changeEventRepository, SourceRepository sourceRepository,
                                   Clock clock) {
        this.jdbc = jdbc;
        this.opportunityRepository = opportunityRepository;
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.changeEventRepository = changeEventRepository;
        this.sourceRepository = sourceRepository;
        this.clock = clock;
    }

    public enum SortOrder { CLOSING_SOONEST, NEWEST, RELEVANCE, PUBLISHED }

    /**
     * Catalogue filters. {@code activeOnly} (the default) shows opportunities that are open or of unknown
     * status and have not passed their closing date.
     */
    public record Filter(String q, UUID organisationId, Sector sector, EventType eventType,
                         ProcurementCategory procurementCategory, String province, String locality,
                         OpportunityStatus status, Boolean activeOnly, LocalDate closingFrom, LocalDate closingTo,
                         LocalDate publishedFrom, LocalDate publishedTo, UUID sourceId, Boolean includeDuplicates,
                         SortOrder sort, za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod submissionMethod, Boolean compulsoryBriefing) {

        /** Filter without the submission/briefing criteria. */
        public Filter(String q, UUID organisationId, Sector sector, EventType eventType,
                      ProcurementCategory procurementCategory, String province, String locality,
                      OpportunityStatus status, Boolean activeOnly, LocalDate closingFrom, LocalDate closingTo,
                      LocalDate publishedFrom, LocalDate publishedTo, UUID sourceId, Boolean includeDuplicates,
                      SortOrder sort) {
            this(q, organisationId, sector, eventType, procurementCategory, province, locality, status, activeOnly,
                    closingFrom, closingTo, publishedFrom, publishedTo, sourceId, includeDuplicates, sort, null, null);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<OpportunitySummaryDto> search(Filter f, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        LocalDate todaySa = today();
        p.addValue("today", todaySa);
        p.addValue("now", Timestamp.from(clock.instant()));
        List<String> where = new ArrayList<>();
        String q = f.q() == null || f.q().isBlank() ? null : f.q().trim();
        if (q != null) {
            where.add("(o.search_vector @@ websearch_to_tsquery('english', :q) or o.reference_number ilike :qLike"
                    + " or exists (select 1 from source_documents sd where sd.opportunity_id = o.id"
                    + " and sd.text_vector @@ websearch_to_tsquery('english', :q)))");
            p.addValue("q", q);
            p.addValue("qLike", "%" + q.replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.organisationId() != null) {
            where.add("o.organisation_id = :organisationId");
            p.addValue("organisationId", f.organisationId());
        }
        if (f.sector() == Sector.MINING) {
            // Mining companies' own listings plus public-sector tenders whose subject is mining.
            where.add("(s.sector = 'MINING' or org.sector = 'MINING' or o.mining_related)");
        } else if (f.sector() == Sector.GOVERNMENT) {
            where.add("(s.sector = 'GOVERNMENT' and coalesce(org.sector, 'GOVERNMENT') <> 'MINING')");
        } else if (f.sector() != null) {
            where.add("(s.sector = :sector or org.sector = :sector)");
            p.addValue("sector", f.sector().name());
        }
        if (f.submissionMethod() != null) {
            where.add("o.submission_method = :submissionMethod");
            p.addValue("submissionMethod", f.submissionMethod().name());
        }
        if (f.compulsoryBriefing() != null) {
            where.add(f.compulsoryBriefing() ? "o.briefing_compulsory = true" : "coalesce(o.briefing_compulsory, false) = false");
        }
        if (f.eventType() != null) {
            where.add("o.event_type = :eventType");
            p.addValue("eventType", f.eventType().name());
        }
        if (f.procurementCategory() != null) {
            where.add("o.procurement_category = :category");
            p.addValue("category", f.procurementCategory().name());
        }
        if (f.province() != null && !f.province().isBlank()) {
            where.add("o.province = :province");
            p.addValue("province", f.province());
        }
        if (f.locality() != null && !f.locality().isBlank()) {
            where.add("o.locality ilike :locality");
            p.addValue("locality", "%" + f.locality().trim() + "%");
        }
        if (f.status() != null) {
            where.add("o.status = :status");
            p.addValue("status", f.status().name());
        } else if (!Boolean.FALSE.equals(f.activeOnly())) {
            where.add("""
                    o.status in ('OPEN', 'UNKNOWN')
                    and (o.closing_at is null or o.closing_at >= :now)
                    and (o.closing_date is null or o.closing_date >= :today)""");
        }
        if (f.closingFrom() != null) {
            where.add("o.closing_date >= :closingFrom");
            p.addValue("closingFrom", f.closingFrom());
        }
        if (f.closingTo() != null) {
            where.add("o.closing_date <= :closingTo");
            p.addValue("closingTo", f.closingTo());
        }
        if (f.publishedFrom() != null) {
            where.add("coalesce(o.published_at, o.first_discovered_at) >= :publishedFrom");
            p.addValue("publishedFrom", Timestamp.from(f.publishedFrom().atStartOfDay(ParsingSupport.SOUTH_AFRICA).toInstant()));
        }
        if (f.publishedTo() != null) {
            where.add("coalesce(o.published_at, o.first_discovered_at) < :publishedTo");
            p.addValue("publishedTo", Timestamp.from(f.publishedTo().plusDays(1).atStartOfDay(ParsingSupport.SOUTH_AFRICA).toInstant()));
        }
        if (f.sourceId() != null) {
            where.add("o.source_id = :sourceId");
            p.addValue("sourceId", f.sourceId());
        }
        if (!Boolean.TRUE.equals(f.includeDuplicates())) {
            where.add("o.duplicate_of_id is null");
        }
        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        SortOrder sort = f.sort() != null ? f.sort() : (q != null ? SortOrder.RELEVANCE : SortOrder.CLOSING_SOONEST);
        String orderBy = switch (sort) {
            case NEWEST -> " order by o.first_discovered_at desc, o.id";
            case PUBLISHED -> " order by o.published_at desc nulls last, o.id";
            case RELEVANCE -> q != null
                    ? " order by ts_rank(o.search_vector, websearch_to_tsquery('english', :q)) desc, o.closing_date asc nulls last, o.id"
                    : " order by o.closing_date asc nulls last, o.id";
            case CLOSING_SOONEST -> " order by o.closing_date asc nulls last, o.closing_at asc nulls last, o.id";
        };
        long total = jdbc.queryForObject("select count(*) " + FROM + whereSql, p, Long.class);
        p.addValue("limit", size);
        p.addValue("offset", (long) page * size);
        List<OpportunitySummaryDto> rows = jdbc.query(SELECT + FROM + whereSql + orderBy + " limit :limit offset :offset",
                p, summaryMapper(todaySa));
        return new PageResponse<>(rows, page, size, total, (int) Math.ceil(total / (double) size));
    }

    /** Summaries for the given ids, in the given order. */
    @Transactional(readOnly = true)
    public List<OpportunitySummaryDto> summaries(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        java.util.Map<UUID, OpportunitySummaryDto> byId = new java.util.HashMap<>();
        jdbc.query(SELECT + FROM + " where o.id in (:ids)", new MapSqlParameterSource("ids", ids), summaryMapper(today()))
                .forEach(s -> byId.put(s.id(), s));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    @Transactional(readOnly = true)
    public OpportunityDetailDto get(UUID id) {
        Opportunity o = opportunityRepository.findById(id).orElseThrow(() -> new NotFoundException("Opportunity", id));
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        OpportunitySummaryDto summary = jdbc.queryForObject(SELECT + FROM + " where o.id = :id", p, summaryMapper(today()));
        Source source = sourceRepository.findById(o.getSourceId()).orElseThrow();
        EvidenceDto evidence = new EvidenceDto(source.getName(), o.getSourceUrl(), o.getEvidenceUrl(),
                source.getIntegrationStatus(), source.getLastVerifiedAt(), o.getFirstDiscoveredAt(), o.getLastCheckedAt(),
                o.getLastSeenAt(), o.getLastChangedAt(), o.getClosingRaw(), o.getContentHash(), source.getLicenceNotes());
        return new OpportunityDetailDto(summary, o.getDescription(), o.getSourceCategoryLabel(), o.getEstimatedValue(),
                o.getCurrency(), o.getContractDuration(), o.getBriefingDetails(), o.getBriefingCompulsory(),
                o.getContactName(), o.getContactEmail(), o.getContactPhone(), o.getMineSite(), o.getDuplicateOfId(),
                documents(id),
                categoryRepository.findByOpportunityId(id).stream()
                        .map(c -> new CategoryDto(c.getCategoryType().name(), c.getCode(), c.getProvenance().name(), c.getConfidence()))
                        .toList(),
                evidence,
                new za.co.oip.opportunities.api.OpportunityDtos.SubmissionDto(o.getSubmissionMethod(), o.getSubmissionChannel(),
                        o.getSubmissionAddress(), o.getTwoEnvelope(), o.getSubmissionProvenance(), o.getSubmissionEvidence()),
                o.getBriefingAt(), o.getBriefingVenue(), o.getSpecialConditions(), o.getMiningEvidence());
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> documents(UUID opportunityId) {
        requireExists(opportunityId);
        java.util.Map<UUID, java.util.Map<String, Object>> processed = new java.util.HashMap<>();
        jdbc.query("select id, opportunity_document_id, status, error, page_count, storage_key from source_documents where opportunity_id = :id",
                new MapSqlParameterSource("id", opportunityId), rs -> {
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("status", rs.getString("status"));
                    m.put("error", rs.getString("error"));
                    m.put("pages", rs.getObject("page_count"));
                    m.put("stored", rs.getString("storage_key") != null);
                    processed.put(rs.getObject("opportunity_document_id", UUID.class), m);
                });
        return documentRepository.findByOpportunityIdOrderByFirstSeenAtAsc(opportunityId).stream()
                .map(d -> {
                    var m = processed.get(d.getId());
                    return new DocumentDto(d.getId(), d.getTitle(), d.getUrl(), d.getDocumentType(), d.getFileFormat(),
                            d.getPublishedAt(), d.getFirstSeenAt(), d.getRemovedAt(),
                            m == null ? null : (UUID) m.get("id"), m == null ? "NOT_PROCESSED" : (String) m.get("status"),
                            m == null ? null : (String) m.get("error"), m == null ? null : (Integer) m.get("pages"),
                            m != null && (Boolean) m.get("stored"));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChangeEventDto> history(UUID opportunityId) {
        requireExists(opportunityId);
        return changeEventRepository.findByOpportunityIdOrderByDetectedAtDesc(opportunityId).stream()
                .map(e -> new ChangeEventDto(e.getId(), e.getFieldName(), e.getOldValue(), e.getNewValue(), e.isMaterial(),
                        e.getDetectedAt()))
                .toList();
    }

    private void requireExists(UUID id) {
        if (!opportunityRepository.existsById(id)) {
            throw new NotFoundException("Opportunity", id);
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ParsingSupport.SOUTH_AFRICA));
    }

    private static RowMapper<OpportunitySummaryDto> summaryMapper(LocalDate today) {
        return (ResultSet rs, int i) -> {
            LocalDate closingDate = rs.getObject("closing_date", LocalDate.class);
            Long days = closingDate == null ? null : ChronoUnit.DAYS.between(today, closingDate);
            OpportunityStatus status = OpportunityStatus.valueOf(rs.getString("status"));
            return new OpportunitySummaryDto(
                    rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("reference_number"),
                    EventType.valueOf(rs.getString("event_type")),
                    ProcurementCategory.valueOf(rs.getString("procurement_category")),
                    status, rs.getBoolean("status_inferred"),
                    rs.getObject("organisation_id", UUID.class), rs.getString("organisation_name"),
                    Sector.valueOf(rs.getString("sector")), rs.getString("province"), rs.getString("locality"),
                    closingDate, instant(rs, "closing_at"), rs.getBoolean("closing_time_specified"), days,
                    urgency(status, days), instant(rs, "published_at"), instant(rs, "first_discovered_at"),
                    rs.getObject("source_id", UUID.class), rs.getString("source_name"),
                    VERIFIED_SOURCE_STATUSES.contains(rs.getString("integration_status")) && rs.getTimestamp("last_verified_at") != null,
                    rs.getString("source_url"), rs.getInt("document_count"),
                    za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod.valueOf(rs.getString("submission_method")),
                    rs.getBoolean("mining_related"), (Boolean) rs.getObject("briefing_compulsory"));
        };
    }

    /** CLOSED, CLOSING_TODAY, URGENT (≤3 days), SOON (≤7 days), OPEN or UNKNOWN (no closing date). */
    static String urgency(OpportunityStatus status, Long days) {
        if (status.isFinal()) return "CLOSED";
        if (days == null) return "UNKNOWN";
        if (days < 0) return "CLOSED";
        if (days == 0) return "CLOSING_TODAY";
        if (days <= 3) return "URGENT";
        if (days <= 7) return "SOON";
        return "OPEN";
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
