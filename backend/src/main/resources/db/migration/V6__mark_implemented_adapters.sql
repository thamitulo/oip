-- Adapters with a tested implementation in the crawling module.
UPDATE source_adapters SET implemented = TRUE
WHERE code IN ('ETENDERS_OCDS', 'HTML_LISTING', 'JSON_API', 'RSS_FEED', 'CSV_LISTING');
