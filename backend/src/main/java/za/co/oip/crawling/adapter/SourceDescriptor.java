package za.co.oip.crawling.adapter;

import tools.jackson.databind.JsonNode;
import za.co.oip.sources.domain.SourceEnums.AdapterType;

import java.util.UUID;

/** Immutable view of a source given to adapters, with its parsed parser configuration. */
public record SourceDescriptor(UUID id, String name, String sourceUrl, AdapterType adapterType,
                               int rateLimitPerMinute, JsonNode config, String organisationName) {

    public String configText(String field, String fallback) {
        JsonNode node = config == null ? null : config.get(field);
        return node == null || node.isNull() ? fallback : node.asString();
    }

    public int configInt(String field, int fallback) {
        JsonNode node = config == null ? null : config.get(field);
        return node == null || !node.isNumber() ? fallback : node.asInt();
    }
}
