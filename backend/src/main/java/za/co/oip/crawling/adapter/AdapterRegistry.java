package za.co.oip.crawling.adapter;

import org.springframework.stereotype.Component;
import za.co.oip.sources.SourceAdapterCatalog;
import za.co.oip.sources.domain.SourceEnums.AdapterType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Looks up the adapter implementation for a source's adapter type. */
@Component
public class AdapterRegistry implements SourceAdapterCatalog {

    private final Map<AdapterType, ProcurementSourceAdapter> adapters = new EnumMap<>(AdapterType.class);

    public AdapterRegistry(List<ProcurementSourceAdapter> implementations) {
        for (ProcurementSourceAdapter adapter : implementations) {
            if (adapters.put(adapter.type(), adapter) != null) {
                throw new IllegalStateException("Duplicate adapter for " + adapter.type());
            }
        }
    }

    public Optional<ProcurementSourceAdapter> find(AdapterType type) {
        return Optional.ofNullable(type == null ? null : adapters.get(type));
    }

    @Override
    public boolean isImplemented(AdapterType adapterType) {
        return adapters.containsKey(adapterType);
    }
}
