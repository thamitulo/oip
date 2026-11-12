package za.co.oip.crawling;

/**
 * Result classification of a crawl run. A failed crawl is never reported as "zero opportunities":
 * only {@link #SUCCESS_ZERO_RESULTS} means the source was read successfully and listed nothing.
 */
public enum CrawlOutcome {
    SUCCESS,
    SUCCESS_ZERO_RESULTS,
    NO_CHANGE,
    CRAWL_FAILED,
    SOURCE_UNAVAILABLE,
    AUTHENTICATION_REQUIRED,
    ACCESS_DENIED,
    RATE_LIMITED,
    PARSER_FAILED,
    BLOCKED_BY_ROBOTS,
    NOT_PERMITTED;

    public boolean isSuccess() {
        return this == SUCCESS || this == SUCCESS_ZERO_RESULTS || this == NO_CHANGE;
    }
}
