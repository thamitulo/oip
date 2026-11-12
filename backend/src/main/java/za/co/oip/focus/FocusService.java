package za.co.oip.focus;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.common.api.PageResponse;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.common.reference.Province;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.opportunities.OpportunityQueryService;
import za.co.oip.opportunities.api.OpportunityDtos.OpportunitySummaryDto;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.security.AuthenticatedUser;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * "My focus": what a user is currently looking for (e.g. state land to lease), saved as keywords, event types,
 * provinces and sectors. Results come from the global catalogue (including document text), so every result keeps
 * its source evidence. Focus areas are personal to the user and scoped to the tenant.
 */
@Service
public class FocusService {

    public record GuidanceLink(String label, String url, String note) {
    }

    public record FocusTemplate(String code, String name, String description, List<String> keywords, List<EventType> eventTypes,
                                List<GuidanceLink> guidance) {
    }

    public record FocusArea(UUID id, String name, String description, String templateCode, List<String> keywords,
                            List<EventType> eventTypes, List<String> provinces, List<String> sectors, boolean includeClosed,
                            boolean notifyMe, Instant createdAt, Instant updatedAt, List<GuidanceLink> guidance) {
    }

    public record FocusRequest(String name, String description, String templateCode, List<String> keywords,
                               List<EventType> eventTypes, List<String> provinces, List<String> sectors, Boolean includeClosed,
                               Boolean notifyMe) {
    }

    /** Verified 2026-11-11 (HTTP 200). */
    static final FocusTemplate STATE_LAND = new FocusTemplate("STATE_LAND_LEASE", "State land to lease",
            "State-owned land, including state farms, advertised for lease or allocation by the Department of Land Reform and "
                    + "Rural Development, and public-sector tenders for leasing state land.",
            List.of("lease of state land", "state land lease", "state farm", "state farms", "lease of farm", "farm to lease",
                    "agricultural land lease", "land allocation", "PLAS"),
            List.of(EventType.LAND_LEASE),
            List.of(new GuidanceLink("DLRRD: Application to lease state farms",
                            "https://www.dlrrd.gov.za/index.php/component/content/article/235-application-to-lease-state-farms?catid=79&Itemid=437",
                            "Official list of state farm adverts with closing dates. Each advert explains eligibility and how to apply."),
                    new GuidanceLink("Declaration of applicant for land allocation (PDF)",
                            "https://www.dlrrd.gov.za/images/Docs/2023/declaration-by-land-applicant.pdf",
                            "Form required by DLRRD under the State Land Lease and Disposal Policy.")));

    static final List<FocusTemplate> TEMPLATES = List.of(STATE_LAND);

    private final NamedParameterJdbcTemplate jdbc;
    private final OpportunityQueryService opportunities;
    private final Clock clock;

    public FocusService(NamedParameterJdbcTemplate jdbc, OpportunityQueryService opportunities, Clock clock) {
        this.jdbc = jdbc;
        this.opportunities = opportunities;
        this.clock = clock;
    }

    public List<FocusTemplate> templates() {
        return TEMPLATES;
    }

    @Transactional(readOnly = true)
    public List<FocusArea> list(AuthenticatedUser user) {
        return jdbc.query("select * from focus_areas where tenant_id = :t and user_id = :u order by created_at",
                new MapSqlParameterSource("t", user.tenantId()).addValue("u", user.userId()), (rs, i) -> map(rs));
    }

    @Transactional
    public FocusArea create(AuthenticatedUser user, FocusRequest r) {
        Normalised n = normalise(r);
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into focus_areas (id, tenant_id, user_id, name, description, template_code, keywords, event_types, provinces,
                  sectors, include_closed, notify, created_at, updated_at)
                values (:id, :t, :u, :name, :desc, :tpl, :kw, :et, :prov, :sec, :closed, :notify, :now, :now)""",
                n.params().addValue("id", id).addValue("t", user.tenantId()).addValue("u", user.userId()).addValue("now", now));
        return get(user, id);
    }

    @Transactional
    public FocusArea update(AuthenticatedUser user, UUID id, FocusRequest r) {
        get(user, id);
        Normalised n = normalise(r);
        jdbc.update("""
                update focus_areas set name = :name, description = :desc, template_code = :tpl, keywords = :kw, event_types = :et,
                  provinces = :prov, sectors = :sec, include_closed = :closed, notify = :notify, updated_at = :now
                where id = :id and tenant_id = :t and user_id = :u""",
                n.params().addValue("id", id).addValue("t", user.tenantId()).addValue("u", user.userId())
                        .addValue("now", Timestamp.from(clock.instant())));
        return get(user, id);
    }

    @Transactional
    public void delete(AuthenticatedUser user, UUID id) {
        int n = jdbc.update("delete from focus_areas where id = :id and tenant_id = :t and user_id = :u",
                new MapSqlParameterSource("id", id).addValue("t", user.tenantId()).addValue("u", user.userId()));
        if (n == 0) throw new NotFoundException("Focus area", id);
    }

    @Transactional(readOnly = true)
    public FocusArea get(AuthenticatedUser user, UUID id) {
        List<FocusArea> found = jdbc.query("select * from focus_areas where id = :id and tenant_id = :t and user_id = :u",
                new MapSqlParameterSource("id", id).addValue("t", user.tenantId()).addValue("u", user.userId()), (rs, i) -> map(rs));
        if (found.isEmpty()) throw new NotFoundException("Focus area", id);
        return found.getFirst();
    }

    /** Opportunities matching a focus area, soonest-closing first. */
    @Transactional(readOnly = true)
    public PageResponse<OpportunitySummaryDto> opportunities(AuthenticatedUser user, UUID id, int page, int size) {
        FocusArea f = get(user, id);
        Query q = query(f, null);
        long total = jdbc.queryForObject("select count(*) " + q.sql(), q.params(), Long.class);
        q.params().addValue("limit", size).addValue("offset", (long) page * size);
        List<UUID> ids = jdbc.queryForList("select o.id " + q.sql()
                + " order by o.closing_date asc nulls last, o.first_discovered_at desc limit :limit offset :offset", q.params(), UUID.class);
        return new PageResponse<>(opportunities.summaries(ids), page, size, total, (int) Math.ceil(total / (double) size));
    }

    /** Ids of opportunities matching the focus that were first discovered after {@code since} (for notifications). */
    @Transactional(readOnly = true)
    public List<UUID> newMatches(FocusArea f, Instant since, int limit) {
        Query q = query(f, since);
        q.params().addValue("limit", limit);
        return jdbc.queryForList("select o.id " + q.sql() + " order by o.first_discovered_at desc limit :limit", q.params(), UUID.class);
    }

    record Query(String sql, MapSqlParameterSource params) {
    }

    Query query(FocusArea f, Instant discoveredSince) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        LocalDate today = LocalDate.now(clock.withZone(ParsingSupport.SOUTH_AFRICA));
        StringBuilder sql = new StringBuilder("""
                from opportunities o join source_registry s on s.id = o.source_id
                left join procurement_organisations org on org.id = o.organisation_id
                where o.duplicate_of_id is null""");
        List<String> any = new ArrayList<>();
        if (!f.eventTypes().isEmpty()) {
            any.add("o.event_type in (:types)");
            p.addValue("types", f.eventTypes().stream().map(Enum::name).toList());
        }
        if (!f.keywords().isEmpty()) {
            any.add("(o.search_vector @@ websearch_to_tsquery('english', :kw) or exists (select 1 from source_documents sd "
                    + "where sd.opportunity_id = o.id and sd.text_vector @@ websearch_to_tsquery('english', :kw)))");
            p.addValue("kw", String.join(" or ", f.keywords().stream().map(k -> "\"" + k.replace("\"", "") + "\"").toList()));
        }
        sql.append(" and (").append(String.join(" or ", any)).append(")");
        if (!f.provinces().isEmpty()) {
            // Opportunities without a published province are kept: the location is often only in the advert.
            sql.append(" and (o.province in (:provinces) or o.province is null)");
            p.addValue("provinces", f.provinces());
        }
        if (!f.sectors().isEmpty()) {
            sql.append(" and (s.sector in (:sectors) or org.sector in (:sectors)")
                    .append(f.sectors().contains("MINING") ? " or o.mining_related)" : ")");
            p.addValue("sectors", f.sectors());
        }
        if (!f.includeClosed()) {
            sql.append(" and o.status in ('OPEN', 'UNKNOWN') and (o.closing_at is null or o.closing_at >= :now)"
                    + " and (o.closing_date is null or o.closing_date >= :today)");
            p.addValue("now", Timestamp.from(clock.instant())).addValue("today", today);
        }
        if (discoveredSince != null) {
            sql.append(" and o.first_discovered_at >= :since");
            p.addValue("since", Timestamp.from(discoveredSince));
        }
        return new Query(sql.toString(), p);
    }

    // ------------------------------------------------------------------ helpers

    private record Normalised(MapSqlParameterSource params) {
    }

    private Normalised normalise(FocusRequest r) {
        String name = r.name() == null ? "" : r.name().trim();
        if (name.isEmpty() || name.length() > 120) throw new BusinessRuleException("Name is required (max 120 characters)");
        List<String> keywords = clean(r.keywords(), 100);
        List<EventType> types = r.eventTypes() == null ? List.of() : r.eventTypes().stream().distinct().toList();
        if (keywords.isEmpty() && types.isEmpty()) {
            throw new BusinessRuleException("Give at least one keyword or opportunity type");
        }
        List<String> provinces = clean(r.provinces(), 20).stream().map(pv -> Province.parse(pv).map(Enum::name)
                .orElseThrow(() -> new BusinessRuleException("Unknown province: " + pv))).distinct().toList();
        List<String> sectors = clean(r.sectors(), 5).stream().map(sct -> sct.toUpperCase(Locale.ROOT)).toList();
        for (String s : sectors) {
            if (!List.of("GOVERNMENT", "MINING", "PRIVATE").contains(s)) throw new BusinessRuleException("Unknown sector: " + s);
        }
        String template = r.templateCode() == null || r.templateCode().isBlank() ? null : r.templateCode().trim();
        if (template != null && TEMPLATES.stream().noneMatch(t -> t.code().equals(template))) {
            throw new BusinessRuleException("Unknown template: " + template);
        }
        String desc = r.description() == null || r.description().isBlank() ? null : r.description().trim();
        if (desc != null && desc.length() > 1000) throw new BusinessRuleException("Description is too long");
        String kw = String.join("|", keywords);
        if (kw.length() > 2000) throw new BusinessRuleException("Too many keywords");
        return new Normalised(new MapSqlParameterSource("name", name).addValue("desc", desc).addValue("tpl", template)
                .addValue("kw", kw.isEmpty() ? null : kw)
                .addValue("et", types.isEmpty() ? null : String.join(",", types.stream().map(Enum::name).toList()))
                .addValue("prov", provinces.isEmpty() ? null : String.join(",", provinces))
                .addValue("sec", sectors.isEmpty() ? null : String.join(",", sectors))
                .addValue("closed", Boolean.TRUE.equals(r.includeClosed())).addValue("notify", !Boolean.FALSE.equals(r.notifyMe())));
    }

    private static List<String> clean(List<String> values, int max) {
        if (values == null) return List.of();
        List<String> out = values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim)
                .filter(v -> v.length() <= 100).distinct().toList();
        if (out.size() > max) throw new BusinessRuleException("At most " + max + " values allowed");
        return out;
    }

    private static FocusArea map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String tpl = rs.getString("template_code");
        List<GuidanceLink> guidance = TEMPLATES.stream().filter(t -> t.code().equals(tpl)).findFirst()
                .map(FocusTemplate::guidance).orElse(List.of());
        return new FocusArea(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"), tpl,
                split(rs.getString("keywords"), "\\|"),
                split(rs.getString("event_types"), ",").stream().map(EventType::valueOf).toList(),
                split(rs.getString("provinces"), ","), split(rs.getString("sectors"), ","),
                rs.getBoolean("include_closed"), rs.getBoolean("notify"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), guidance);
    }

    private static List<String> split(String s, String sep) {
        return s == null || s.isBlank() ? List.of() : Arrays.stream(s.split(sep)).filter(v -> !v.isBlank()).toList();
    }

    /** All focus areas with notifications on (for the notification job). */
    @Transactional(readOnly = true)
    public List<Owned> notifiable() {
        return jdbc.query("""
                select f.*, f.tenant_id as owner_tenant, f.user_id as owner_user from focus_areas f
                join users u on u.id = f.user_id join tenant_users tu on tu.user_id = f.user_id and tu.tenant_id = f.tenant_id
                where f.notify and u.status = 'ACTIVE' and tu.status = 'ACTIVE'""", new MapSqlParameterSource(),
                (rs, i) -> new Owned(rs.getObject("owner_tenant", UUID.class), rs.getObject("owner_user", UUID.class), map(rs)));
    }

    public record Owned(UUID tenantId, UUID userId, FocusArea focus) {
    }
}
