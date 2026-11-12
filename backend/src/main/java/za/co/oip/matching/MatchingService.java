package za.co.oip.matching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.companies.CompanyProfileService;
import za.co.oip.companies.api.CompanyProfileDto;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.matching.MatchingEngine.Label;
import za.co.oip.matching.MatchingEngine.Weights;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Computes and caches tenant-specific matches. A cached match is reused while neither the company profile nor
 * the opportunity has changed (profile fingerprint + opportunity content hash).
 */
@Service
public class MatchingService {

    private final MatchingEngine engine;
    private final CompanyProfileService profileService;
    private final NamedParameterJdbcTemplate jdbc;
    private final OpportunityMatchRepository matchRepository;
    private final MatchSettingsRepository settingsRepository;
    private final JsonMapper json;
    private final Clock clock;

    public MatchingService(MatchingEngine engine, CompanyProfileService profileService, NamedParameterJdbcTemplate jdbc,
                           OpportunityMatchRepository matchRepository, MatchSettingsRepository settingsRepository,
                           JsonMapper json, Clock clock) {
        this.engine = engine;
        this.profileService = profileService;
        this.jdbc = jdbc;
        this.matchRepository = matchRepository;
        this.settingsRepository = settingsRepository;
        this.json = json;
        this.clock = clock;
    }

    public record MatchDto(UUID opportunityId, int score, Label label, Map<String, Double> dimensions, List<String> matched,
                           List<String> missing, List<String> disqualifiers, Map<String, Object> aiExplanation,
                           Instant computedAt) {
    }

    public record SettingsDto(int weightServices, int weightIndustry, int weightCapability, int weightGeography,
                              int weightRegistrations, int weightOther, int strongThreshold, int possibleThreshold) {
    }

    /** The match for one opportunity, computing it if missing or stale. */
    @Transactional
    public MatchDto match(UUID tenantId, UUID opportunityId) {
        Context ctx = context(tenantId);
        OppRow row = loadOpportunities(List.of(opportunityId)).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Opportunity", opportunityId));
        upsert(tenantId, ctx, row, requirements(List.of(opportunityId)));
        return toDto(matchRepository.findByTenantIdAndOpportunityId(tenantId, opportunityId).orElseThrow());
    }

    /** Computes matches for all active opportunities that have none or a stale one. Returns how many were (re)computed. */
    @Transactional
    public int refresh(UUID tenantId, int limit) {
        Context ctx = context(tenantId);
        List<UUID> ids = jdbc.queryForList("""
                select o.id from opportunities o
                left join opportunity_matches m on m.opportunity_id = o.id and m.tenant_id = :tenant
                where o.status in ('OPEN', 'UNKNOWN') and o.duplicate_of_id is null
                  and (o.closing_date is null or o.closing_date >= :today)
                  and (m.id is null or m.profile_version <> :pv or m.opportunity_hash <> o.content_hash
                       or m.computed_at < o.updated_at)
                limit :limit""", new MapSqlParameterSource().addValue("tenant", tenantId).addValue("today", today())
                .addValue("pv", ctx.profileVersion).addValue("limit", limit), UUID.class);
        for (int i = 0; i < ids.size(); i += 500) {
            List<UUID> chunk = ids.subList(i, Math.min(ids.size(), i + 500));
            Map<UUID, List<MatchingEngine.Requirement>> reqs = requirements(chunk);
            for (OppRow row : loadOpportunities(chunk)) {
                upsert(tenantId, ctx, row, reqs);
            }
        }
        return ids.size();
    }

    public record Recommended(UUID opportunityId, int score, Label label, int disqualifierCount) {
    }

    /** Highest-scoring active opportunities for the tenant. */
    @Transactional(readOnly = true)
    public List<Recommended> recommended(UUID tenantId, int minScore, int limit, int offset) {
        return jdbc.query("""
                select m.opportunity_id, m.score, m.label, jsonb_array_length(m.disqualifiers) dq
                from opportunity_matches m join opportunities o on o.id = m.opportunity_id
                where m.tenant_id = :tenant and m.score >= :min and o.status in ('OPEN', 'UNKNOWN')
                  and (o.closing_date is null or o.closing_date >= :today)
                order by m.score desc, o.closing_date asc nulls last limit :limit offset :offset""",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("min", minScore).addValue("today", today())
                        .addValue("limit", limit).addValue("offset", offset),
                (rs, i) -> new Recommended(rs.getObject(1, UUID.class), rs.getInt(2), Label.valueOf(rs.getString(3)), rs.getInt(4)));
    }

    @Transactional(readOnly = true)
    public long countRecommended(UUID tenantId, int minScore) {
        Long n = jdbc.queryForObject("""
                select count(*) from opportunity_matches m join opportunities o on o.id = m.opportunity_id
                where m.tenant_id = :tenant and m.score >= :min and o.status in ('OPEN', 'UNKNOWN')
                  and (o.closing_date is null or o.closing_date >= :today)""",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("min", minScore).addValue("today", today()), Long.class);
        return n == null ? 0 : n;
    }

    @Transactional
    public void storeAiExplanation(UUID tenantId, UUID opportunityId, Map<String, Object> explanation) {
        matchRepository.findByTenantIdAndOpportunityId(tenantId, opportunityId).ifPresent(m ->
                m.setAiExplanation(json.writeValueAsString(explanation)));
    }

    @Transactional(readOnly = true)
    public SettingsDto settings(UUID tenantId) {
        MatchSettings s = settingsRepository.findById(tenantId).orElseGet(() -> defaults(tenantId));
        return new SettingsDto(s.getWeightServices(), s.getWeightIndustry(), s.getWeightCapability(), s.getWeightGeography(),
                s.getWeightRegistrations(), s.getWeightOther(), s.getStrongThreshold(), s.getPossibleThreshold());
    }

    @Transactional
    public SettingsDto updateSettings(UUID tenantId, SettingsDto d) {
        int sum = d.weightServices() + d.weightIndustry() + d.weightCapability() + d.weightGeography() + d.weightRegistrations()
                + d.weightOther();
        if (sum != 100) {
            throw new BusinessRuleException("Weights must add up to 100 (currently " + sum + ")");
        }
        if (d.possibleThreshold() >= d.strongThreshold() || d.possibleThreshold() < 1 || d.strongThreshold() > 100) {
            throw new BusinessRuleException("Thresholds must satisfy 1 ≤ possible < strong ≤ 100");
        }
        MatchSettings s = settingsRepository.findById(tenantId).orElseGet(() -> defaults(tenantId));
        s.setWeightServices(d.weightServices());
        s.setWeightIndustry(d.weightIndustry());
        s.setWeightCapability(d.weightCapability());
        s.setWeightGeography(d.weightGeography());
        s.setWeightRegistrations(d.weightRegistrations());
        s.setWeightOther(d.weightOther());
        s.setStrongThreshold(d.strongThreshold());
        s.setPossibleThreshold(d.possibleThreshold());
        s.setUpdatedAt(clock.instant());
        settingsRepository.save(s);
        // Weights changed: invalidate cached matches so they are recomputed.
        jdbc.update("update opportunity_matches set profile_version = -1 where tenant_id = :t", new MapSqlParameterSource("t", tenantId));
        return settings(tenantId);
    }

    @Transactional(readOnly = true)
    public List<UUID> tenantIds() {
        return jdbc.queryForList("select id from tenants where status = 'ACTIVE'", new MapSqlParameterSource(), UUID.class);
    }

    // ------------------------------------------------------------------

    private record Context(MatchingEngine.Profile profile, Weights weights, long profileVersion) {
    }

    private record OppRow(UUID id, String title, String description, String category, String province, boolean mining,
                          String sector, LocalDate closingDate, Boolean briefing, String contentHash) {
    }

    private Context context(UUID tenantId) {
        CompanyProfileDto p = profileService.get(tenantId);
        Integer bbbee = p.bbbeeLevel() != null && p.bbbeeLevel().name().startsWith("LEVEL_")
                ? Integer.parseInt(p.bbbeeLevel().name().substring(6)) : null;
        MatchingEngine.Profile profile = new MatchingEngine.Profile(nz(p.services()), nz(p.products()), nz(p.industries()),
                nz(p.commodities()), nz(p.operatingRegions()),
                nz(p.capabilities()).stream().map(c -> new MatchingEngine.Capability(c.name(), c.description())).toList(),
                nz(p.certifications()).stream().map(CompanyProfileDto.CertificationDto::name).toList(),
                nz(p.registrations()).stream().map(r -> new MatchingEngine.Registration(r.registrationType().name(),
                        r.registrationBody(), r.registrationNumber())).toList(),
                p.cidbGrading(), bbbee);
        SettingsDto s = settings(tenantId);
        Weights w = new Weights(s.weightServices(), s.weightIndustry(), s.weightCapability(), s.weightGeography(),
                s.weightRegistrations(), s.weightOther(), s.strongThreshold(), s.possibleThreshold());
        long version = json.writeValueAsString(p.legalName() + profile + w).hashCode() & 0xffffffffL;
        return new Context(profile, w, version);
    }

    private List<OppRow> loadOpportunities(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("""
                select o.id, o.title, o.description, o.source_category_label, o.province, o.mining_related,
                       coalesce(case when org.sector = 'MINING' or s.sector = 'MINING' then 'MINING' end, s.sector) sector,
                       o.closing_date, o.briefing_compulsory, o.content_hash
                from opportunities o join source_registry s on s.id = o.source_id
                left join procurement_organisations org on org.id = o.organisation_id where o.id in (:ids)""",
                new MapSqlParameterSource("ids", ids),
                (rs, i) -> new OppRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), rs.getString(7), rs.getObject(8, LocalDate.class),
                        (Boolean) rs.getObject(9), rs.getString(10)));
    }

    private Map<UUID, List<MatchingEngine.Requirement>> requirements(List<UUID> opportunityIds) {
        Map<UUID, List<MatchingEngine.Requirement>> out = new HashMap<>();
        jdbc.query("select opportunity_id, requirement_type, value, mandatory, evidence from opportunity_requirements where opportunity_id in (:ids)",
                new MapSqlParameterSource("ids", opportunityIds), rs -> {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(new MatchingEngine.Requirement(
                            rs.getString(2), rs.getString(3), (Boolean) rs.getObject(4), rs.getString(5)));
                });
        return out;
    }

    /**
     * Writes the match with a single atomic upsert, so concurrent refreshes (a request and the background job) cannot
     * collide. An existing AI explanation is kept only while the profile version is unchanged.
     */
    private void upsert(UUID tenantId, Context ctx, OppRow row, Map<UUID, List<MatchingEngine.Requirement>> reqs) {
        Long days = row.closingDate() == null ? null : java.time.temporal.ChronoUnit.DAYS.between(today(), row.closingDate());
        var opp = new MatchingEngine.Opportunity(row.title(), row.description(), row.category(), row.province(), row.mining(),
                row.sector(), days, row.briefing(), reqs.getOrDefault(row.id(), List.of()));
        MatchingEngine.Result r = engine.score(ctx.profile(), opp, ctx.weights());
        jdbc.update("""
                insert into opportunity_matches (id, tenant_id, opportunity_id, score, label, dimension_scores, matched, missing,
                  disqualifiers, profile_version, opportunity_hash, computed_at)
                values (:id, :t, :o, :score, :label, cast(:dims as jsonb), cast(:matched as jsonb), cast(:missing as jsonb),
                  cast(:dq as jsonb), :pv, :hash, :now)
                on conflict (tenant_id, opportunity_id) do update set score = excluded.score, label = excluded.label,
                  dimension_scores = excluded.dimension_scores, matched = excluded.matched, missing = excluded.missing,
                  disqualifiers = excluded.disqualifiers,
                  ai_explanation = case when opportunity_matches.profile_version = excluded.profile_version
                                        then opportunity_matches.ai_explanation end,
                  profile_version = excluded.profile_version, opportunity_hash = excluded.opportunity_hash,
                  computed_at = excluded.computed_at""",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", tenantId).addValue("o", row.id())
                        .addValue("score", r.score()).addValue("label", r.label().name())
                        .addValue("dims", json.writeValueAsString(r.dimensions())).addValue("matched", json.writeValueAsString(r.matched()))
                        .addValue("missing", json.writeValueAsString(r.missing()))
                        .addValue("dq", json.writeValueAsString(r.disqualifiers())).addValue("pv", ctx.profileVersion())
                        .addValue("hash", row.contentHash()).addValue("now", java.sql.Timestamp.from(clock.instant())));
    }

    private MatchDto toDto(OpportunityMatch m) {
        return new MatchDto(m.getOpportunityId(), m.getScore(), Label.valueOf(m.getLabel()),
                json.readValue(m.getDimensionScores(), new TypeReference<Map<String, Double>>() { }),
                json.readValue(m.getMatched(), new TypeReference<List<String>>() { }),
                json.readValue(m.getMissing(), new TypeReference<List<String>>() { }),
                json.readValue(m.getDisqualifiers(), new TypeReference<List<String>>() { }),
                m.getAiExplanation() == null ? null : json.readValue(m.getAiExplanation(), new TypeReference<Map<String, Object>>() { }),
                m.getComputedAt());
    }

    public Optional<MatchDto> cached(UUID tenantId, UUID opportunityId) {
        return matchRepository.findByTenantIdAndOpportunityId(tenantId, opportunityId).map(this::toDto);
    }

    private MatchSettings defaults(UUID tenantId) {
        MatchSettings s = new MatchSettings();
        s.setTenantId(tenantId);
        s.setUpdatedAt(clock.instant());
        return s;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ParsingSupport.SOUTH_AFRICA));
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }

    // ------------------------------------------------------------------ persistence

    @Getter
    @Setter
    @Entity(name = "OpportunityMatch")
    @Table(name = "opportunity_matches")
    public static class OpportunityMatch {
        @Id
        private UUID id = UUID.randomUUID();
        @Column(name = "tenant_id", nullable = false)
        private UUID tenantId;
        @Column(name = "opportunity_id", nullable = false)
        private UUID opportunityId;
        private int score;
        private String label;
        @ColumnTransformer(write = "?::jsonb")
        @Column(name = "dimension_scores", columnDefinition = "jsonb")
        private String dimensionScores;
        @ColumnTransformer(write = "?::jsonb")
        @Column(columnDefinition = "jsonb")
        private String matched;
        @ColumnTransformer(write = "?::jsonb")
        @Column(columnDefinition = "jsonb")
        private String missing;
        @ColumnTransformer(write = "?::jsonb")
        @Column(columnDefinition = "jsonb")
        private String disqualifiers;
        @ColumnTransformer(write = "?::jsonb")
        @Column(name = "ai_explanation", columnDefinition = "jsonb")
        private String aiExplanation;
        @Column(name = "profile_version", nullable = false)
        private long profileVersion;
        @Column(name = "opportunity_hash", nullable = false)
        private String opportunityHash;
        @Column(name = "computed_at", nullable = false)
        private Instant computedAt;
    }

    @Getter
    @Setter
    @Entity(name = "MatchSettings")
    @Table(name = "tenant_match_settings")
    public static class MatchSettings {
        @Id
        @Column(name = "tenant_id")
        private UUID tenantId;
        @Column(name = "weight_services")
        private int weightServices = 30;
        @Column(name = "weight_industry")
        private int weightIndustry = 20;
        @Column(name = "weight_capability")
        private int weightCapability = 20;
        @Column(name = "weight_geography")
        private int weightGeography = 10;
        @Column(name = "weight_registrations")
        private int weightRegistrations = 10;
        @Column(name = "weight_other")
        private int weightOther = 10;
        @Column(name = "strong_threshold")
        private int strongThreshold = 70;
        @Column(name = "possible_threshold")
        private int possibleThreshold = 45;
        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;
    }

    public interface OpportunityMatchRepository extends JpaRepository<OpportunityMatch, UUID> {
        Optional<OpportunityMatch> findByTenantIdAndOpportunityId(UUID tenantId, UUID opportunityId);
    }

    public interface MatchSettingsRepository extends JpaRepository<MatchSettings, UUID> {
    }
}
