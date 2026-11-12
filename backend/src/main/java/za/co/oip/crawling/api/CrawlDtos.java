package za.co.oip.crawling.api;

import za.co.oip.crawling.CrawlOutcome;
import za.co.oip.crawling.execution.CrawlRecords.RunStatus;
import za.co.oip.crawling.execution.CrawlRecords.TriggerType;
import za.co.oip.sources.domain.SourceEnums.IntegrationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CrawlDtos {

    private CrawlDtos() {
    }

    public record CrawlJobAccepted(UUID jobId, UUID sourceId, Instant requestedAt) {
    }

    public record CrawlRunDto(UUID id, UUID jobId, UUID sourceId, String sourceName, TriggerType trigger,
                              Instant startedAt, Instant finishedAt, RunStatus status, CrawlOutcome outcome,
                              int recordsFound, int recordsDiscovered, int recordsUpdated, int recordsUnchanged,
                              int recordsRejected, Integer httpStatus, String contentHash, String message) {
    }

    public record CrawlErrorDto(UUID id, UUID runId, UUID sourceId, String sourceName, String errorType,
                                String message, String url, String itemReference, Instant occurredAt) {
    }

    public record ObservationDto(UUID id, String url, Integer httpStatus, String contentType, String contentHash,
                                 Long bytes, Instant fetchedAt, String excerpt) {
    }

    public record CrawlRunDetailDto(CrawlRunDto run, List<CrawlErrorDto> errors, List<ObservationDto> observations,
                                    String executionLog) {
    }

    /** Health of a source from the operator's point of view. */
    public record SourceHealthDto(UUID sourceId, String sourceName, IntegrationStatus integrationStatus,
                                  boolean enabled, Instant lastAttemptedCrawlAt, Instant lastSuccessfulCrawlAt,
                                  CrawlOutcome lastOutcome, Integer consecutiveFailures, Instant nextCrawlAt,
                                  long opportunityCount, String health) {
    }
}
