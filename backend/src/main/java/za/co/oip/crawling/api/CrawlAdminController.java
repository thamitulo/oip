package za.co.oip.crawling.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import za.co.oip.common.api.PageResponse;
import za.co.oip.crawling.api.CrawlDtos.CrawlErrorDto;
import za.co.oip.crawling.api.CrawlDtos.CrawlJobAccepted;
import za.co.oip.crawling.api.CrawlDtos.CrawlRunDetailDto;
import za.co.oip.crawling.api.CrawlDtos.CrawlRunDto;
import za.co.oip.crawling.api.CrawlDtos.SourceHealthDto;
import za.co.oip.crawling.execution.CrawlRecords.CrawlJob;
import za.co.oip.crawling.execution.CrawlRecords.RunStatus;
import za.co.oip.crawling.execution.CrawlService;
import za.co.oip.crawling.execution.CrawlService.DryRunResult;
import za.co.oip.security.CurrentUser;

import java.util.List;
import java.util.UUID;

/** Crawl operations. Restricted to platform administrators by SecurityConfig ({@code /api/v1/admin/**}). */
@Tag(name = "Admin: crawling")
@RestController
@RequestMapping("/api/v1/admin")
public class CrawlAdminController {

    private final CrawlService crawlService;
    private final CrawlQueryService queryService;

    public CrawlAdminController(CrawlService crawlService, CrawlQueryService queryService) {
        this.crawlService = crawlService;
        this.queryService = queryService;
    }

    @Operation(summary = "Run a crawl of a source now (asynchronous)")
    @PostMapping("/sources/{id}/crawl")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CrawlJobAccepted crawl(@PathVariable UUID id) {
        CrawlJob job = crawlService.requestManualCrawl(id, CurrentUser.require().userId());
        return new CrawlJobAccepted(job.getId(), job.getSourceId(), job.getRequestedAt());
    }

    @Operation(summary = "Test a source: fetch and parse without storing anything")
    @PostMapping("/sources/{id}/test")
    public DryRunResult test(@PathVariable UUID id) {
        return crawlService.dryRun(id);
    }

    @Operation(summary = "Crawl history")
    @GetMapping("/crawls")
    public PageResponse<CrawlRunDto> crawls(@RequestParam(required = false) UUID sourceId,
                                            @RequestParam(required = false) RunStatus status,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        return queryService.runs(sourceId, status, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200)));
    }

    @Operation(summary = "Crawl run detail: errors, fetched evidence and execution log")
    @GetMapping("/crawls/{id}")
    public CrawlRunDetailDto crawlRun(@PathVariable("id") UUID runId) {
        return queryService.runDetail(runId);
    }

    @Operation(summary = "Recent crawl and parser errors")
    @GetMapping("/source-errors")
    public PageResponse<CrawlErrorDto> errors(@RequestParam(required = false) UUID sourceId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return queryService.errors(sourceId, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200)));
    }

    @Operation(summary = "Health of every source: last outcome, freshness and failure streak")
    @GetMapping("/source-health")
    public List<SourceHealthDto> health() {
        return queryService.sourceHealth();
    }
}
