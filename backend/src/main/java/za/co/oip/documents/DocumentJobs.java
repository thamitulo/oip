package za.co.oip.documents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background document processing and retention. Disabled with {@code oip.documents.processing-enabled=false}. */
@Component
@ConditionalOnProperty(prefix = "oip.documents", name = "processing-enabled", havingValue = "true", matchIfMissing = true)
public class DocumentJobs {

    private static final Logger log = LoggerFactory.getLogger(DocumentJobs.class);

    private final DocumentProcessingService service;
    private final int batchSize;

    public DocumentJobs(DocumentProcessingService service, @Value("${oip.documents.batch-size:10}") int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "${oip.documents.poll-interval:PT1M}")
    public void processQueue() {
        int n = service.processQueued(batchSize);
        if (n > 0) {
            log.info("Processed {} document(s)", n);
        }
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "Africa/Johannesburg")
    public void expireOriginals() {
        int n = service.expireOriginals();
        if (n > 0) {
            log.info("Deleted {} expired original document(s); extracted text retained", n);
        }
    }
}
