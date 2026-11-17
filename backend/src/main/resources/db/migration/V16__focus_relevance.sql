-- =====================================================================
-- V16: Make "My focus" precise.
-- Matching any keyword anywhere in tender document text pulled in most of the catalogue (boilerplate such as
-- "application", "development" or "oil" appears in almost every bid pack), so the specific items were buried.
-- Focus results are now ranked: title/reference first, then description, and document-only matches are opt-in.
-- =====================================================================

-- Title + reference only, so a title match can be ranked above a description match.
ALTER TABLE opportunities ADD COLUMN title_vector TSVECTOR GENERATED ALWAYS AS (
    to_tsvector('english', COALESCE(reference_number, '') || ' ' || COALESCE(title, ''))) STORED;
CREATE INDEX ix_opportunities_title_search ON opportunities USING GIN (title_vector);

-- Off by default: matches found only inside documents are noisy. Existing focus areas get the precise behaviour.
ALTER TABLE focus_areas ADD COLUMN search_documents BOOLEAN NOT NULL DEFAULT FALSE;
-- Words that rule an opportunity out (matched against title and description), e.g. "vehicle" for a fuel focus.
ALTER TABLE focus_areas ADD COLUMN exclude_keywords VARCHAR(2000);
