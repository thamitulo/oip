package za.co.oip.crawling.adapter;

import za.co.oip.sources.domain.SourceEnums.AdapterType;

/**
 * Reads one kind of procurement source and returns normalised procurement events.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>fetch only through {@link CrawlContext#http()} (robots.txt, rate limits, SSRF protection);</li>
 *   <li>record what they fetched with {@link CrawlContext#observe} as evidence;</li>
 *   <li>return a failure outcome — never an empty success — when the content could not be read or did
 *       not have the expected structure;</li>
 *   <li>populate only values present in the source.</li>
 * </ul>
 * Adapters do not persist anything; ingestion, de-duplication and change detection happen afterwards.
 */
public interface ProcurementSourceAdapter {

    AdapterType type();

    AdapterResult crawl(SourceDescriptor source, CrawlContext context);
}
