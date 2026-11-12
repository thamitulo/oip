package za.co.oip.crawling.http;

import za.co.oip.crawling.CrawlOutcome;

/** A fetch failed. The outcome says how, so that the run can be classified correctly. */
public class FetchException extends Exception {

    private final CrawlOutcome outcome;
    private final Integer httpStatus;
    private final String url;

    public FetchException(CrawlOutcome outcome, String message, String url, Integer httpStatus) {
        super(message);
        this.outcome = outcome;
        this.url = url;
        this.httpStatus = httpStatus;
    }

    public FetchException(CrawlOutcome outcome, String message, String url, Throwable cause) {
        super(message, cause);
        this.outcome = outcome;
        this.url = url;
        this.httpStatus = null;
    }

    public CrawlOutcome outcome() {
        return outcome;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String url() {
        return url;
    }
}
