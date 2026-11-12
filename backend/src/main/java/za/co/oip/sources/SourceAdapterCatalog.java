package za.co.oip.sources;

import za.co.oip.sources.domain.SourceEnums.AdapterType;

/** Tells the source registry which adapter types have a working implementation. Implemented by the crawler. */
public interface SourceAdapterCatalog {

    boolean isImplemented(AdapterType adapterType);
}
