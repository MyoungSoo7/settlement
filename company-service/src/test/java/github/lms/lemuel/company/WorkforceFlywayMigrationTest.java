package github.lms.lemuel.company;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class WorkforceFlywayMigrationTest {

    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();
    private static final Path MIGRATION_DIRECTORY = REPOSITORY_ROOT.resolve("company-service/src/main/resources/db/migration");
    private static final Path OLD_MIGRATION = MIGRATION_DIRECTORY.resolve("V20260804090000__seed_workforce_2026_06.sql");
    private static final Path PROVENANCE_DDL_MIGRATION = MIGRATION_DIRECTORY.resolve("V20260806110000__add_workforce_dataset_provenance.sql");
    private static final Path NEW_MIGRATION = MIGRATION_DIRECTORY.resolve("V20260806120000__replace_workforce_with_seoul_it_2026_06.sql");
    private static final String OLD_SHA_256 = "F5A243FC9C3F46F2788199432592F524718465E57866F60D511FCE445558E95F";
    private static final String SOURCE_SHA_256 = "2AAC48EF155D268D544EB8A5BA04CCA201A1E1806A847C5772307775B4657F2B";
    private static final Set<String> ALLOWED_INDUSTRY_CODES = Set.of(
            "642004", "721000", "722000", "722001", "722002", "722003",
            "722004", "722005", "723001", "724000", "729000", "940926");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^V([^_]+)__.+\\.sql$");
    private static final Pattern WORKFORCE_TUPLE = Pattern.compile(
            "\\('((?:''|[^'])*)', '([0-9]{6})', '([0-9]{6})', "
                    + "'(?:''|[^'])*', '(?:''|[^'])*', '([^']*)', "
                    + "(?:'(?:''|[^'])*'|NULL), '([^']*)', [0-9]+, [0-9]+\\)");
    private static final Pattern BUILD_METADATA_TUPLE = Pattern.compile("""
            INSERT\\s+INTO\\s+workforce_aggregate_build
            \\s*\\(snapshot_month,\\s*status,\\s*source_row_count,\\s*accepted_row_count,\\s*rejected_row_count,\\s*built_at,
            \\s*source_release_date,\\s*source_sha256,\\s*raw_source_row_count,\\s*coverage_scope,\\s*region_scope,\\s*industry_scope\\)
            \\s*VALUES\\s*\\(
            \\s*'([^']+)',\\s*'BUILDING',\\s*(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*NOW\\(\\),
            \\s*DATE\\s*'([^']+)',\\s*'([A-F0-9]{64})',\\s*(\\d+),\\s*'([^']+)',\\s*'([^']+)',\\s*'([^']+)'\\)
            """, Pattern.CASE_INSENSITIVE | Pattern.COMMENTS);

    @Test
    void replacementMigrationPreservesTheFrozenSeoulItCohortContract() throws Exception {
        byte[] oldMigration = Files.readAllBytes(OLD_MIGRATION);
        assertThat(oldMigration).hasSize(845_068);
        assertThat(sha256(oldMigration)).isEqualTo(OLD_SHA_256);

        List<Path> migrations;
        try (var paths = Files.list(MIGRATION_DIRECTORY)) {
            migrations = paths.filter(path -> path.getFileName().toString().endsWith(".sql")).toList();
        }
        Set<String> versions = new HashSet<>();
        for (Path migration : migrations) {
            Matcher version = VERSION_PATTERN.matcher(migration.getFileName().toString());
            if (version.matches()) {
                assertThat(versions.add(version.group(1)))
                        .as("duplicate Flyway version for %s", migration.getFileName())
                        .isTrue();
            }
        }

        assertThat(Files.exists(NEW_MIGRATION)).isTrue();
        assertThat(Files.exists(PROVENANCE_DDL_MIGRATION)).isTrue();
        String ddl = Files.readString(PROVENANCE_DDL_MIGRATION, StandardCharsets.UTF_8);
        for (String column : List.of("source_release_date", "source_sha256", "raw_source_row_count",
                "coverage_scope", "region_scope", "industry_scope")) {
            assertThat(ddl).contains("ADD COLUMN IF NOT EXISTS " + column, "COMMENT ON COLUMN workforce_aggregate_build." + column);
        }
        byte[] bytes = Files.readAllBytes(NEW_MIGRATION);
        assertThat(bytes).hasSizeLessThan(10 * 1024 * 1024);
        String sql = new String(bytes, StandardCharsets.UTF_8);
        assertThat(sql.getBytes(StandardCharsets.UTF_8)).containsExactly(bytes);

        Matcher tupleMatcher = WORKFORCE_TUPLE.matcher(sql);
        Set<String> businessKeys = new HashSet<>();
        Set<String> industryCodes = new HashSet<>();
        int tupleCount = 0;
        while (tupleMatcher.find()) {
            tupleCount++;
            businessKeys.add(tupleMatcher.group(1) + "\\u0000" + tupleMatcher.group(2));
            industryCodes.add(tupleMatcher.group(3));
            assertThat(tupleMatcher.group(4)).isEqualTo("서울특별시");
            assertThat(tupleMatcher.group(5)).isEqualTo("2026-06");
        }
        assertThat(tupleCount).isEqualTo(11_313);
        assertThat(businessKeys).hasSize(11_313);
        assertThat(industryCodes).containsExactlyInAnyOrderElementsOf(ALLOWED_INDUSTRY_CODES);

        Matcher metadata = BUILD_METADATA_TUPLE.matcher(sql);
        assertThat(metadata.find()).isTrue();
        assertThat(metadata.group(1)).isEqualTo("2026-06");
        assertThat(metadata.group(2)).isEqualTo("11318");
        assertThat(metadata.group(3)).isEqualTo("11313");
        assertThat(metadata.group(4)).isEqualTo("5");
        assertThat(metadata.group(5)).isEqualTo("2026-07-23");
        assertThat(metadata.group(6)).isEqualTo(SOURCE_SHA_256);
        assertThat(metadata.group(7)).isEqualTo("593127");
        assertThat(metadata.group(8)).isEqualTo("SEOUL_IT_FULL");
        assertThat(metadata.group(9)).isEqualTo("SEOUL");
        assertThat(metadata.group(10)).isEqualTo("SOFTWARE_IT_SERVICE");
        assertThat(metadata.find()).isFalse();
        assertThat(sql).contains("0.095::numeric");
        assertThat(sql).contains("actual_fingerprint", "246de1b02d14f86ccf751c96d3956059");
        assertThat(sql).doesNotContain("COUNT(*) FROM company_workforce WHERE snapshot_month = '2026-06') <> 4247");
        assertThat(sql).doesNotContain("ALTER TABLE workforce_aggregate_build");
        assertThat(Pattern.compile("(?im)^\\s*(?:BEGIN|COMMIT)\\s*;").matcher(sql).find()).isFalse();
        assertThat(Pattern.compile("\\b(?:double\\s+precision|real)\\b", Pattern.CASE_INSENSITIVE).matcher(sql).find()).isFalse();
        assertThat(sql.toLowerCase(Locale.ROOT)).doesNotContain("c:\\users");
        assertThat(sql).doesNotContain("국민연금공단_국민연금 가입 사업장 내역_20260723.csv");

        int lock = sql.indexOf("LOCK TABLE company_workforce,");
        int precondition = sql.indexOf("actual_fingerprint IS DISTINCT FROM");
        int workforceDelete = sql.indexOf("DELETE FROM company_workforce WHERE snapshot_month = '2026-06'");
        int buildStart = sql.indexOf("INSERT INTO workforce_aggregate_build");
        int cohortInsert = sql.indexOf("INSERT INTO company_workforce");
        int completion = sql.indexOf("SET status = 'COMPLETE'");
        assertThat(sql).contains("IN SHARE ROW EXCLUSIVE MODE;");
        assertThat(lock).isGreaterThanOrEqualTo(0);
        assertThat(precondition).isGreaterThan(lock);
        assertThat(workforceDelete).isGreaterThan(precondition);
        assertThat(buildStart).isGreaterThan(workforceDelete);
        assertThat(cohortInsert).isGreaterThan(buildStart);
        assertThat(completion).isGreaterThan(cohortInsert);

        assertThat(trackedResourceAndMigrationFiles()).noneMatch(path -> path.contains("국민연금공단_국민연금 가입 사업장 내역_20260723.csv"));
    }

    private static List<String> trackedResourceAndMigrationFiles() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "-C", REPOSITORY_ROOT.toString(), "ls-files", "company-service/src/main/resources")
                .redirectErrorStream(true)
                .start();
        List<String> paths;
        try (var output = process.inputReader(StandardCharsets.UTF_8)) {
            paths = output.lines()
                    .filter(path -> path.contains("/db/migration/") || path.endsWith(".csv"))
                    .toList();
        }
        assertThat(process.waitFor()).isZero();
        return paths;
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format("%02X", value));
        }
        return hex.toString();
    }

    private static Path locateRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git")) && Files.isDirectory(current.resolve("company-service"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root was not found from user.dir");
    }
}
