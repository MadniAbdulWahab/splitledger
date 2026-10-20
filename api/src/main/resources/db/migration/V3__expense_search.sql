-- Full-text and typo-tolerant search over expenses.

-- Trigram matching for typos ("Pizerria"), short names ("Lidl") and German compounds
-- ("Einkauf" inside "Wocheneinkauf"). Trusted extension: the database owner may create it.
create extension if not exists pg_trgm;

-- Two configurations on purpose:
--   german: stems word forms ("Einkäufe" -> einkauf) and drops stopwords ("bei", "für")
--   simple: keeps every word as written, so names and shops match exactly
-- Description weighs more (A) than notes (B) in the ranking. STORED means PostgreSQL computes the
-- value on insert, so the application cannot forget to fill it.
alter table expense
    add column search_document tsvector generated always as (
        setweight(to_tsvector('german', description), 'A') ||
        setweight(to_tsvector('simple', description), 'A') ||
        setweight(to_tsvector('german', coalesce(notes, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(notes, '')), 'B')
    ) stored;

create index expense_search_idx on expense using gin (search_document);
create index expense_description_trgm_idx on expense using gin (description gin_trgm_ops);
