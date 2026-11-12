package za.co.oip.sources;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import za.co.oip.audit.AuditService;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.common.error.Exceptions.ConflictException;
import za.co.oip.common.error.Exceptions.NotFoundException;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.procurement.domain.ProcurementOrganisation;
import za.co.oip.procurement.domain.ProcurementOrganisationRepository;
import za.co.oip.sources.api.SourceDtos.SourceDto;
import za.co.oip.sources.api.SourceDtos.SourceRequest;
import za.co.oip.sources.domain.CrawlSchedule;
import za.co.oip.sources.domain.CrawlScheduleRepository;
import za.co.oip.sources.domain.Source;
import za.co.oip.sources.domain.SourceEnums.IntegrationStatus;
import za.co.oip.sources.domain.SourceRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Administration of the source registry (platform administrators only). */
@Service
public class SourceAdminService {

    private final SourceRepository sourceRepository;
    private final CrawlScheduleRepository scheduleRepository;
    private final ProcurementOrganisationRepository organisationRepository;
    private final SourceAdapterCatalog adapterCatalog;
    private final AuditService auditService;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public SourceAdminService(SourceRepository sourceRepository, CrawlScheduleRepository scheduleRepository,
                              ProcurementOrganisationRepository organisationRepository,
                              SourceAdapterCatalog adapterCatalog, AuditService auditService, JsonMapper jsonMapper,
                              Clock clock) {
        this.sourceRepository = sourceRepository;
        this.scheduleRepository = scheduleRepository;
        this.organisationRepository = organisationRepository;
        this.adapterCatalog = adapterCatalog;
        this.auditService = auditService;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<SourceDto> search(Sector sector, IntegrationStatus status, Boolean enabled, String q, Pageable pageable) {
        String query = q == null || q.isBlank() ? null : q.trim();
        return sourceRepository.search(sector, status, enabled, query, pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public SourceDto get(UUID id) {
        return toDto(load(id));
    }

    @Transactional(readOnly = true)
    public Source load(UUID id) {
        return sourceRepository.findById(id).orElseThrow(() -> new NotFoundException("Source", id));
    }

    @Transactional
    public SourceDto create(SourceRequest request) {
        if (sourceRepository.existsByNameIgnoreCase(request.name().trim())) {
            throw new ConflictException("A source with this name already exists");
        }
        Source source = new Source();
        apply(request, source);
        sourceRepository.save(source);

        Instant now = clock.instant();
        CrawlSchedule schedule = new CrawlSchedule();
        schedule.setSourceId(source.getId());
        schedule.setIntervalMinutes(request.crawlIntervalMinutes() == null ? 1440 : request.crawlIntervalMinutes());
        schedule.setNextRunAt(now);
        schedule.setCreatedAt(now);
        schedule.setUpdatedAt(now);
        scheduleRepository.save(schedule);

        auditService.record(null, "SOURCE_CREATED", "Source", source.getId(), Map.of("name", source.getName()));
        return toDto(source);
    }

    @Transactional
    public SourceDto update(UUID id, SourceRequest request) {
        Source source = load(id);
        boolean wasEnabled = source.isEnabled();
        apply(request, source);
        sourceRepository.saveAndFlush(source);
        if (request.crawlIntervalMinutes() != null) {
            scheduleRepository.findBySourceId(id).ifPresent(s -> {
                s.setIntervalMinutes(request.crawlIntervalMinutes());
                s.setUpdatedAt(clock.instant());
            });
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("integrationStatus", source.getIntegrationStatus());
        details.put("enabled", source.isEnabled());
        if (wasEnabled != source.isEnabled()) {
            details.put("enabledChanged", true);
        }
        auditService.record(null, "SOURCE_UPDATED", "Source", id, details);
        return toDto(source);
    }

    private void apply(SourceRequest r, Source s) {
        if (r.organisationId() != null && !organisationRepository.existsById(r.organisationId())) {
            throw new BusinessRuleException("Unknown organisation: " + r.organisationId());
        }
        String config = blankToNull(r.parserConfig());
        if (config != null) {
            try {
                jsonMapper.readTree(config);
            } catch (JacksonException e) {
                throw new BusinessRuleException("Parser configuration is not valid JSON");
            }
        }
        s.setName(r.name().trim());
        s.setOrganisationId(r.organisationId());
        s.setSourceUrl(blankToNull(r.sourceUrl()));
        s.setSourceType(r.sourceType());
        s.setSector(r.sector());
        s.setIndustry(blankToNull(r.industry()));
        s.setAccessMethod(r.accessMethod());
        s.setIntegrationStatus(r.integrationStatus());
        s.setAuthenticationRequired(r.authenticationRequired());
        s.setPubliclyAccessible(r.publiclyAccessible());
        s.setRobotsPolicy(r.robotsPolicy());
        s.setTermsReviewStatus(r.termsReviewStatus());
        s.setLicenceNotes(blankToNull(r.licenceNotes()));
        s.setRateLimitPerMinute(r.rateLimitPerMinute());
        s.setAdapterType(r.adapterType());
        s.setParserConfig(config);
        s.setReliability(r.reliability());
        s.setPriority(r.priority());
        s.setVerificationNotes(blankToNull(r.verificationNotes()));
        if (r.shouldMarkVerified()) {
            s.setLastVerifiedAt(clock.instant());
        }
        if (r.isEnabled()) {
            List<String> blockers = new ArrayList<>(s.crawlBlockers());
            if (s.getAdapterType() != null && !adapterCatalog.isImplemented(s.getAdapterType())) {
                blockers.add("Adapter " + s.getAdapterType() + " is not implemented yet");
            }
            if (!blockers.isEmpty()) {
                throw new BusinessRuleException("Source cannot be enabled: " + String.join("; ", blockers));
            }
        }
        s.setEnabled(r.isEnabled());
    }

    SourceDto toDto(Source s) {
        String orgName = s.getOrganisationId() == null ? null
                : organisationRepository.findById(s.getOrganisationId()).map(ProcurementOrganisation::getName).orElse(null);
        CrawlSchedule schedule = scheduleRepository.findBySourceId(s.getId()).orElse(null);
        List<String> blockers = new ArrayList<>(s.crawlBlockers());
        if (s.getAdapterType() != null && !adapterCatalog.isImplemented(s.getAdapterType())) {
            blockers.add("Adapter " + s.getAdapterType() + " is not implemented yet");
        }
        return new SourceDto(s.getId(), s.getName(), s.getOrganisationId(), orgName, s.getSourceUrl(), s.getSourceType(),
                s.getSector(), s.getCountry(), s.getIndustry(), s.getAccessMethod(), s.getIntegrationStatus(),
                s.isEnabled(), s.getAuthenticationRequired(), s.getPubliclyAccessible(), s.getRobotsPolicy(),
                s.getTermsReviewStatus(), s.getLicenceNotes(), s.getRateLimitPerMinute(), s.getAdapterType(),
                s.getParserConfig(), s.getReliability(), s.getPriority(), s.getVerificationNotes(),
                s.getLastVerifiedAt(), s.getLastAttemptedCrawlAt(), s.getLastSuccessfulCrawlAt(),
                schedule == null ? null : schedule.getIntervalMinutes(),
                schedule == null ? null : schedule.getNextRunAt(),
                schedule == null ? null : schedule.getConsecutiveFailures(),
                blockers, s.getUpdatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
