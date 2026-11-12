package za.co.oip.sources.domain;

import java.util.EnumSet;
import java.util.Set;

/** Source registry enumerations. Values mirror database CHECK constraints. */
public final class SourceEnums {

    private SourceEnums() {
    }

    public enum SourceType {
        TENDER_LISTING, ESD_PORTAL, SUPPLIER_PORTAL, PROCUREMENT_PAGE, API, RSS_FEED, DOCUMENT_LISTING, AGGREGATOR,
        COMPANY_WEBSITE
    }

    public enum AccessMethod {
        PUBLIC_HTML, PUBLIC_API, RSS, JS_RENDERED, DOCUMENT_DOWNLOAD, CSV_EXCEL, LOGIN_REQUIRED, REGISTRATION_REQUIRED,
        LICENSED_FEED, MANUAL, UNKNOWN
    }

    public enum IntegrationStatus {
        ACTIVE_PUBLIC, ACTIVE_API, AUTHORISED_INTEGRATION, LOGIN_REQUIRED, REGISTRATION_REQUIRED, PENDING_VERIFICATION,
        TEMPORARILY_UNAVAILABLE, DISABLED, UNSUPPORTED;

        /** Statuses under which automated collection is permitted. */
        public static final Set<IntegrationStatus> CRAWLABLE = EnumSet.of(ACTIVE_PUBLIC, ACTIVE_API, AUTHORISED_INTEGRATION);

        public boolean isCrawlable() {
            return CRAWLABLE.contains(this);
        }
    }

    public enum RobotsPolicy { UNKNOWN, ALLOWED, DISALLOWED, NOT_PRESENT }

    public enum TermsReviewStatus { NOT_REVIEWED, APPROVED, RESTRICTED, PROHIBITED }

    public enum Reliability { UNKNOWN, LOW, MEDIUM, HIGH }

    public enum AdapterType {
        ETENDERS_OCDS, ETENDERS_PORTAL, HTML_LISTING, JSON_API, RSS_FEED, CSV_LISTING, BROWSER_RENDERED, AUTHORISED_PLATFORM
    }
}
