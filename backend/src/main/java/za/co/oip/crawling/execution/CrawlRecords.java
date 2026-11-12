package za.co.oip.crawling.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import za.co.oip.crawling.CrawlOutcome;

import java.time.Instant;
import java.util.UUID;

/** Persistent records of crawl requests, executions, errors and fetched evidence. */
public final class CrawlRecords {

    private CrawlRecords() {
    }

    public enum TriggerType { SCHEDULED, MANUAL }

    public enum JobStatus { QUEUED, RUNNING, COMPLETED, SKIPPED }

    public enum RunStatus { RUNNING, SUCCEEDED, FAILED }

    /** A request to crawl a source, scheduled or manual. */
    @Getter
    @Setter
    @Entity(name = "CrawlJob")
    @Table(name = "crawl_jobs")
    public static class CrawlJob {
        @Id
        private UUID id = UUID.randomUUID();
        @Column(name = "source_id", nullable = false)
        private UUID sourceId;
        @Enumerated(EnumType.STRING)
        @Column(name = "trigger_type", nullable = false)
        private TriggerType triggerType;
        @Column(name = "requested_by")
        private UUID requestedBy;
        @Column(name = "requested_at", nullable = false)
        private Instant requestedAt;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private JobStatus status = JobStatus.QUEUED;
        @Column(name = "completed_at")
        private Instant completedAt;
        @Column(name = "skip_reason")
        private String skipReason;
    }

    /** One execution of a crawl job with its metrics and outcome. */
    @Getter
    @Setter
    @Entity(name = "CrawlJobRun")
    @Table(name = "crawl_job_runs")
    public static class CrawlJobRun {
        @Id
        private UUID id = UUID.randomUUID();
        @Column(name = "job_id", nullable = false)
        private UUID jobId;
        @Column(name = "source_id", nullable = false)
        private UUID sourceId;
        @Column(nullable = false)
        private int attempt = 1;
        @Column(name = "started_at", nullable = false)
        private Instant startedAt;
        @Column(name = "finished_at")
        private Instant finishedAt;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private RunStatus status = RunStatus.RUNNING;
        @Enumerated(EnumType.STRING)
        private CrawlOutcome outcome;
        @Column(name = "records_found", nullable = false)
        private int recordsFound;
        @Column(name = "records_discovered", nullable = false)
        private int recordsDiscovered;
        @Column(name = "records_updated", nullable = false)
        private int recordsUpdated;
        @Column(name = "records_unchanged", nullable = false)
        private int recordsUnchanged;
        @Column(name = "records_rejected", nullable = false)
        private int recordsRejected;
        @Column(name = "http_status")
        private Integer httpStatus;
        @Column(name = "content_hash")
        private String contentHash;
        private String message;
        @Column(name = "execution_log")
        private String executionLog;
    }

    @Getter
    @Setter
    @Entity(name = "CrawlError")
    @Table(name = "crawl_errors")
    public static class CrawlError {
        @Id
        private UUID id = UUID.randomUUID();
        @Column(name = "run_id", nullable = false)
        private UUID runId;
        @Column(name = "source_id", nullable = false)
        private UUID sourceId;
        @Column(name = "error_type", nullable = false)
        private String errorType;
        @Column(nullable = false)
        private String message;
        private String url;
        @Column(name = "item_reference")
        private String itemReference;
        @Column(name = "occurred_at", nullable = false)
        private Instant occurredAt;
    }

    /** Evidence of a fetch: URL, status, hash and an excerpt of the content. */
    @Getter
    @Setter
    @Entity(name = "SourceObservation")
    @Table(name = "source_observations")
    public static class SourceObservation {
        @Id
        private UUID id = UUID.randomUUID();
        @Column(name = "source_id", nullable = false)
        private UUID sourceId;
        @Column(name = "run_id")
        private UUID runId;
        @Column(nullable = false)
        private String url;
        @Column(name = "http_status")
        private Integer httpStatus;
        @Column(name = "content_type")
        private String contentType;
        @Column(name = "content_hash")
        private String contentHash;
        private Long bytes;
        @Column(name = "fetched_at", nullable = false)
        private Instant fetchedAt;
        private String excerpt;
    }
}
