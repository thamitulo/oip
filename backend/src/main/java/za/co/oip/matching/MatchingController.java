package za.co.oip.matching;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.common.api.PageResponse;
import za.co.oip.matching.MatchingService.MatchDto;
import za.co.oip.matching.MatchingService.Recommended;
import za.co.oip.matching.MatchingService.SettingsDto;
import za.co.oip.opportunities.OpportunityQueryService;
import za.co.oip.opportunities.api.OpportunityDtos.OpportunitySummaryDto;
import za.co.oip.security.CurrentUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Tag(name = "Matching")
@Validated
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAuthority('OPPORTUNITIES_VIEW')")
public class MatchingController {

    private final MatchingService service;
    private final OpportunityQueryService opportunities;

    public MatchingController(MatchingService service, OpportunityQueryService opportunities) {
        this.service = service;
        this.opportunities = opportunities;
    }

    /** A recommended opportunity with its match summary. */
    public record RecommendedDto(OpportunitySummaryDto opportunity, int score, MatchingEngine.Label label, int disqualifierCount) {
    }

    @Operation(summary = "Explainable match of an opportunity against your company profile")
    @GetMapping("/opportunities/{id}/match")
    public MatchDto match(@PathVariable UUID id) {
        return service.match(CurrentUser.require().tenantId(), id);
    }

    @Operation(summary = "Active opportunities that best match your company profile")
    @GetMapping({"/opportunities/recommended", "/opportunities/matches"})
    public PageResponse<RecommendedDto> recommended(@RequestParam(defaultValue = "30") @Min(0) @Max(100) int minScore,
                                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID tenant = CurrentUser.require().tenantId();
        service.refresh(tenant, 5000);
        List<Recommended> recs = service.recommended(tenant, minScore, size, page * size);
        Map<UUID, OpportunitySummaryDto> byId = opportunities.summaries(recs.stream().map(Recommended::opportunityId).toList())
                .stream().collect(Collectors.toMap(OpportunitySummaryDto::id, Function.identity()));
        long total = service.countRecommended(tenant, minScore);
        List<RecommendedDto> content = recs.stream().filter(r -> byId.containsKey(r.opportunityId()))
                .map(r -> new RecommendedDto(byId.get(r.opportunityId()), r.score(), r.label(), r.disqualifierCount())).toList();
        return new PageResponse<>(content, page, size, total, (int) Math.ceil(total / (double) size));
    }

    @Operation(summary = "Matching weights and thresholds for your business")
    @GetMapping("/companies/match-settings")
    public SettingsDto settings() {
        return service.settings(CurrentUser.require().tenantId());
    }

    @Operation(summary = "Change matching weights (must add up to 100) and thresholds")
    @PutMapping("/companies/match-settings")
    @PreAuthorize("hasAuthority('TENANT_MANAGE')")
    public SettingsDto updateSettings(@Valid @RequestBody SettingsDto settings) {
        return service.updateSettings(CurrentUser.require().tenantId(), settings);
    }
}
