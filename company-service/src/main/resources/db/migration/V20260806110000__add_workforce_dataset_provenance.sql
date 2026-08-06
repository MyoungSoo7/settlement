-- V20260806110000: workforce aggregate build source provenance
-- Nullable columns distinguish a frozen, generated cohort from an ordinary admin rebuild.

ALTER TABLE workforce_aggregate_build ADD COLUMN IF NOT EXISTS source_release_date DATE;
ALTER TABLE workforce_aggregate_build ADD COLUMN IF NOT EXISTS source_sha256 VARCHAR(64);
ALTER TABLE workforce_aggregate_build ADD COLUMN IF NOT EXISTS raw_source_row_count BIGINT;
ALTER TABLE workforce_aggregate_build ADD COLUMN IF NOT EXISTS coverage_scope VARCHAR(32);
ALTER TABLE workforce_aggregate_build ADD COLUMN IF NOT EXISTS region_scope VARCHAR(32);
ALTER TABLE workforce_aggregate_build ADD COLUMN IF NOT EXISTS industry_scope VARCHAR(64);

COMMENT ON COLUMN workforce_aggregate_build.source_release_date IS
    'Public dataset release date for a generated workforce cohort; NULL for ordinary admin rebuilds.';
COMMENT ON COLUMN workforce_aggregate_build.source_sha256 IS
    'SHA-256 of the immutable public source payload; NULL for ordinary admin rebuilds.';
COMMENT ON COLUMN workforce_aggregate_build.raw_source_row_count IS
    'Raw public source row count before cohort filtering; NULL for ordinary admin rebuilds.';
COMMENT ON COLUMN workforce_aggregate_build.coverage_scope IS
    'Named generated cohort coverage scope; NULL for ordinary admin rebuilds.';
COMMENT ON COLUMN workforce_aggregate_build.region_scope IS
    'Named generated cohort regional scope; NULL for ordinary admin rebuilds.';
COMMENT ON COLUMN workforce_aggregate_build.industry_scope IS
    'Named generated cohort industry scope; NULL for ordinary admin rebuilds.';
