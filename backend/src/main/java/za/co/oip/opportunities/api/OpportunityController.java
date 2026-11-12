package za.co.oip.opportunities.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.common.api.PageResponse;
import za.co.oip.common.error.Exceptions.BusinessRuleException;
import za.co.oip.crawling.adapter.ParsingSupport;
import za.co.oip.opportunities.OpportunityQueryService;
import za.co.oip.opportunities.OpportunityQueryService.Filter;
import za.co.oip.opportunities.OpportunityQueryService.SortOrder;
import za.co.oip.opportunities.api.OpportunityDtos.ChangeEventDto;
import za.co.oip.opportunities.api.OpportunityDtos.DocumentDto;
import za.co.oip.opportunities.api.OpportunityDtos.OpportunityDetailDto;
import za.co.oip.opportunities.api.OpportunityDtos.OpportunitySummaryDto;
import za.co.oip.opportunities.domain.OpportunityEnums.EventType;
import za.co.oip.opportunities.domain.OpportunityEnums.OpportunityStatus;
import za.co.oip.opportunities.domain.OpportunityEnums.ProcurementCategory;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The global opportunity catalogue. Opportunities are shared by all tenants and read-only here. */
@Tag(name = "Opportunities")
@Validated
@RestController
@RequestMapping("/api/v1/opportunities")
@PreAuthorize("hasAuthority('OPPORTUNITIES_VIEW')")
public class OpportunityController {

    private final OpportunityQueryService service;
    private final Clock clock;

    public OpportunityController(OpportunityQueryService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Operation(summary = "Browse opportunities with filters, sorting and pagination")
    @GetMapping
    public PageResponse<OpportunitySummaryDto> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID organisationId,
            @RequestParam(required = false) Sector sector,
            @RequestParam(required = false) EventType eventType,
            @RequestParam(required = false) ProcurementCategory procurementCategory,
            @RequestParam(required = false) String province,
            @RequestParam(required = false) String locality,
            @RequestParam(required = false) OpportunityStatus status,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate closingFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate closingTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate publishedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate publishedTo,
            @RequestParam(required = false) UUID sourceId,
            @RequestParam(required = false) Boolean includeDuplicates,
            @RequestParam(required = false) SortOrder sort,
            @RequestParam(required = false) za.co.oip.opportunities.domain.OpportunityEnums.SubmissionMethod submissionMethod,
            @RequestParam(required = false) Boolean compulsoryBriefing,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Filter filter = new Filter(q, organisationId, sector, eventType, procurementCategory, province, locality, status,
                activeOnly, closingFrom, closingTo, publishedFrom, publishedTo, sourceId, includeDuplicates, sort,
                submissionMethod, compulsoryBriefing);
        return service.search(filter, page, size);
    }

    @Operation(summary = "Full-text search across reference numbers, titles, categories and descriptions")
    @GetMapping("/search")
    public PageResponse<OpportunitySummaryDto> search(@RequestParam String q,
                                                      @RequestParam(required = false) Sector sector,
                                                      @RequestParam(required = false) Boolean activeOnly,
                                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        if (q.isBlank()) {
            throw new BusinessRuleException("A search term is required");
        }
        return service.search(new Filter(q, null, sector, null, null, null, null, null, activeOnly, null, null, null,
                null, null, null, SortOrder.RELEVANCE), page, size);
    }

    @Operation(summary = "Active opportunities closing within the next N days (default 7)")
    @GetMapping("/closing-soon")
    public PageResponse<OpportunitySummaryDto> closingSoon(@RequestParam(defaultValue = "7") @Min(0) @Max(90) int days,
                                                           @RequestParam(required = false) Sector sector,
                                                           @RequestParam(defaultValue = "0") @Min(0) int page,
                                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        LocalDate today = LocalDate.now(clock.withZone(ParsingSupport.SOUTH_AFRICA));
        return service.search(new Filter(null, null, sector, null, null, null, null, null, true, today,
                today.plusDays(days), null, null, null, null, SortOrder.CLOSING_SOONEST), page, size);
    }

    @Operation(summary = "Opportunity detail with documents, categories and source evidence")
    @GetMapping("/{id}")
    public OpportunityDetailDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "Documents published with an opportunity")
    @GetMapping("/{id}/documents")
    public List<DocumentDto> documents(@PathVariable UUID id) {
        return service.documents(id);
    }

    @Operation(summary = "Changes detected at the source (closing date, documents, status, ...)")
    @GetMapping("/{id}/history")
    public List<ChangeEventDto> history(@PathVariable UUID id) {
        return service.history(id);
    }
}
