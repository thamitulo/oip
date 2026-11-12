package za.co.oip.crawling.adapter;

import za.co.oip.crawling.CrawlOutcome;

import java.util.ArrayList;
import java.util.List;

/**
 * What an adapter produced. {@code outcome} must be a failure value whenever the source could not be
 * read or parsed with confidence; it may only be a success value when the listing was actually read.
 */
public record AdapterResult(CrawlOutcome outcome,
                            List<NormalisedOpportunity> opportunities,
                            List<ItemError> itemErrors,
                            Integer httpStatus,
                            String contentHash,
                            String message) {

    /** A problem with one listed item; the rest of the listing is still usable. */
    public record ItemError(String errorType, String message, String url, String itemReference) {
    }

    public static AdapterResult success(List<NormalisedOpportunity> items, List<ItemError> errors, Integer httpStatus,
                                        String contentHash) {
        CrawlOutcome outcome = items.isEmpty() && errors.isEmpty() ? CrawlOutcome.SUCCESS_ZERO_RESULTS : CrawlOutcome.SUCCESS;
        return new AdapterResult(outcome, List.copyOf(items), List.copyOf(errors), httpStatus, contentHash, null);
    }

    public static AdapterResult failure(CrawlOutcome outcome, String message, Integer httpStatus) {
        if (outcome.isSuccess()) {
            throw new IllegalArgumentException("failure() requires a failure outcome");
        }
        return new AdapterResult(outcome, List.of(), List.of(), httpStatus, null, message);
    }

    /** Collects items and per-item errors while parsing. */
    public static final class Collector {
        public final List<NormalisedOpportunity> items = new ArrayList<>();
        public final List<ItemError> errors = new ArrayList<>();
    }
}
