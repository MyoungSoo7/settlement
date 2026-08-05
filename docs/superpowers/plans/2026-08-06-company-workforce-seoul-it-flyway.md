# Seoul IT Workforce Flyway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the misleading 4,247-row national sample for the 2026-07-23 NPS release with the complete, explicitly scoped Seoul software/IT-service cohort and calculate salary statistics with the correct 2026 contribution rate.

**Architecture:** Keep the committed migration immutable and add one forward migration generated from the ignored local CSV. A date-effective Java contribution-rate policy is the calculation source for runtime rebuilds; generated SQL embeds the verified rate for the fixed snapshot. Dataset provenance and coverage metadata make the comparison query accept only the complete Seoul-IT cohort.

**Tech Stack:** Java 21, Spring Boot, JdbcTemplate, PostgreSQL/Flyway, Python 3 standard library, JUnit 5, Gradle.

## Global Constraints

- Source release is `2026-07-23`; every source row must have `snapshot_month=2026-06`.
- Source SHA-256 is `2AAC48EF155D268D544EB8A5BA04CCA201A1E1806A847C5772307775B4657F2B`; raw row count excluding the header is `593127`.
- The strict Seoul software/IT-service selection has `11318` candidate rows, `11313` accepted unique rows, and `5` rejected rows (3 non-positive monetary/headcount rows and 2 duplicate-key rows resolved by last-row-wins).
- Allowed industry codes are exactly `642004`, `721000`, `722000`, `722001`, `722002`, `722003`, `722004`, `722005`, `723001`, `724000`, `729000`, and `940926`.
- Address selection is road-name first and lot-number fallback; selected addresses must parse to `서울특별시`.
- The tracked migration contains SQL rows only. The CSV stays ignored and must not be copied into another tracked path.
- Never modify or rename `V20260804090000__seed_workforce_2026_06.sql`; correction is forward-only.
- Money uses `BigDecimal`/PostgreSQL `NUMERIC`, explicit `HALF_UP`, decimal string construction, and no `float`, `double`, `REAL`, or `DOUBLE PRECISION`.
- Contribution rate is `0.09` through `2025-12` and `0.095` from `2026-01` through `2026-12`. Unsupported months return no rate rather than guessing.
- The migration dataset is labeled `SEOUL_IT_FULL`, with `region_scope=SEOUL` and `industry_scope=SOFTWARE_IT_SERVICE`.
- Existing user changes and staging are not reverted or included in task commits.

---

### Task 1: Date-effective NPS contribution-rate domain policy

**Files:**
- Create: `company-service/src/main/java/github/lms/lemuel/company/domain/NpsContributionRate.java`
- Modify: `company-service/src/main/java/github/lms/lemuel/company/domain/CompanyWorkforce.java`
- Create: `company-service/src/test/java/github/lms/lemuel/company/domain/NpsContributionRateTest.java`
- Modify: `company-service/src/test/java/github/lms/lemuel/company/domain/CompanyWorkforceTest.java`

**Interfaces:**
- Produces: `NpsContributionRate.rateOf(YearMonth): Optional<BigDecimal>`.
- Changes: `CompanyWorkforce.estimatedAnnualSalary()` resolves the rate from `snapshotMonth` and returns empty when headcount is zero or the rate is unsupported.

- [ ] **Step 1: Write failing rate-boundary tests**

```java
@ParameterizedTest
@CsvSource({"2025-12,0.09", "2026-01,0.095", "2026-06,0.095", "2026-12,0.095"})
void returnsRateForSupportedMonth(String month, String expected) {
    assertEquals(0, new BigDecimal(expected).compareTo(
            NpsContributionRate.rateOf(YearMonth.parse(month)).orElseThrow()));
}

@Test
void refusesUnsupportedOrNullMonth() {
    assertTrue(NpsContributionRate.rateOf(YearMonth.of(2027, 1)).isEmpty());
    assertTrue(NpsContributionRate.rateOf(null).isEmpty());
}
```

- [ ] **Step 2: Write a failing salary regression test**

Create a June 2026 workforce with monthly billed amount `950000`, headcount `10`, and assert annual salary `12000000`. The existing 9% implementation must fail with `12666667`.

- [ ] **Step 3: Run RED tests**

Run: `./gradlew :company-service:test --tests '*NpsContributionRateTest' --tests '*CompanyWorkforceTest' --console=plain`

Expected: compilation failure because `NpsContributionRate` is missing and/or the June salary assertion differs.

- [ ] **Step 4: Implement the minimal policy and domain integration**

Use immutable brackets with `new BigDecimal("0.09")` and `new BigDecimal("0.095")`. Resolve the rate before dividing and keep `divide(..., 0, RoundingMode.HALF_UP)`.

- [ ] **Step 5: Run GREEN tests and commit**

Run the Step 3 command and expect all selected tests to pass.

Commit: `fix(company): apply 2026 NPS contribution rate`

---

### Task 2: NUMERIC-only aggregate rebuild

**Files:**
- Modify: `company-service/src/main/java/github/lms/lemuel/company/adapter/out/persistence/WorkforceAggregatePersistenceAdapter.java`
- Modify: `company-service/src/test/java/github/lms/lemuel/company/adapter/out/persistence/WorkforceComparisonPersistenceAdaptersTest.java`

**Interfaces:**
- Consumes: `NpsContributionRate.rateOf(YearMonth)` from Task 1.
- Changes: aggregate rebuild binds the resolved rate as a PostgreSQL `NUMERIC` parameter and refuses unsupported months.
- Preserves: continuous median semantics, including averaging the two middle values for even populations.

- [ ] **Step 1: Write failing adapter tests**

Add a rebuild test for month `2026-06` that captures JdbcTemplate arguments and requires the bound rate to compare equal to `0.095`. Add assertions that the emitted aggregate SQL contains neither `double precision` nor literal `0.09`.

- [ ] **Step 2: Run RED test**

Run: `./gradlew :company-service:test --tests '*WorkforceComparisonPersistenceAdaptersTest' --console=plain`

Expected: failure because current SQL contains `0.09` and `double precision` and binds no rate.

- [ ] **Step 3: Replace percentile_cont casts with an exact median pipeline**

Build `metric_values(axis, level, group_key, metric, metric_value)` from `grouped`, rank each numeric value with `ROW_NUMBER()` and `COUNT(*)`, retain row numbers `(count+1)/2` and `(count+2)/2`, then store `ROUND(AVG(metric_value), 2)` with `MAX(group_count)` as sample size.

- [ ] **Step 4: Bind rate from the domain policy**

Parse the rebuild month with `YearMonth.parse`, require a supported rate, and pass that BigDecimal to both aggregate and percentile SQL executions. Do not introduce a SQL or database rate table.

- [ ] **Step 5: Run GREEN test and commit**

Run the Step 2 command and expect all tests to pass.

Commit: `fix(company): keep workforce aggregates numeric`

---

### Task 3: Deterministic Seoul-IT Flyway generator

**Files:**
- Modify: `scripts/etl/gen-workforce-seed.py`
- Create: `scripts/etl/test_gen_workforce_seed.py`

**Interfaces:**
- Produces CLI: `python scripts/etl/gen-workforce-seed.py --csv <path> --output <new-sql-path> --release-date 2026-07-23 --snapshot-month 2026-06`.
- Produces a new SQL file only; refuses to overwrite an existing path.
- Exposes pure parsing/filter/render functions importable by `unittest` without reading or writing production paths at import time.

- [ ] **Step 1: Write failing unit tests using temporary CP949 CSV fixtures**

Cover these literal cases: accepted Seoul road-address row with code `722000`; accepted lot-address fallback row with code `724000`; rejected Gyeonggi row; rejected Seoul computer-retail row `523532`; rejected withdrawn row; rejected non-positive amount; duplicate key where the last row wins; mixed `2026-05`/`2026-06` input aborts; an existing output file aborts.

Assert the accepted fixture renders only `snapshot_month='2026-06'`, rate `0.095`, coverage `SEOUL_IT_FULL`, and the fixture SHA-256/count metadata.

- [ ] **Step 2: Run RED tests**

Run: `python -m unittest scripts.etl.test_gen_workforce_seed -v`

Expected: failure because the current module performs immediate I/O through hard-coded paths and has no CLI/filter interface.

- [ ] **Step 3: Refactor and implement filtering**

Use `argparse`, strict CP949 decoding, the 12-code frozen set, road-first address selection, `parse_region`, last-row-wins de-duplication, and explicit counters. Verify every source row month before rendering.

- [ ] **Step 4: Add deterministic migration rendering**

Render fixed-order rows and chunks, SQL-escape text, embed SHA-256 and counts, add Description/Author/Date/Rollback header, use `0.095`, use the Task 2 exact numeric median SQL, and write with exclusive creation (`open(..., "x")`).

- [ ] **Step 5: Run GREEN tests and commit**

Run the Step 2 command and expect all tests to pass.

Commit: `refactor(company): generate scoped workforce migrations`

---

### Task 4: Forward Flyway replacement and provenance

**Files:**
- Create: `company-service/src/main/resources/db/migration/V20260806120000__replace_workforce_with_seoul_it_2026_06.sql`
- Create: `company-service/src/test/java/github/lms/lemuel/company/adapter/out/persistence/WorkforceFlywayMigrationTest.java`

**Interfaces:**
- Consumes: ignored source CSV from the original checkout and the Task 3 generator.
- Produces: 11,313 unique `company_workforce` rows for `2026-06`, complete aggregate/percentile rows, and build provenance.
- Schema additions on `workforce_aggregate_build`: nullable `source_release_date DATE`, `source_sha256 VARCHAR(64)`, `raw_source_row_count BIGINT`, `coverage_scope VARCHAR(32)`, `region_scope VARCHAR(32)`, and `industry_scope VARCHAR(64)`.

- [ ] **Step 1: Write failing migration contract tests**

The test reads Flyway migration resources and asserts that the old migration checksum/content is unchanged, the new version exists, and no two migration versions collide. If PostgreSQL Testcontainers support already exists, apply migrations and assert the database behavior; otherwise add a focused PostgreSQL integration test using the project's existing container pattern.

Database assertions after migration:

```text
company_workforce snapshot 2026-06 count = 11313
all rows sido = 서울특별시
all industry_code values belong to the 12-code set
build status = COMPLETE
source_release_date = 2026-07-23
source_sha256 = 2AAC48EF155D268D544EB8A5BA04CCA201A1E1806A847C5772307775B4657F2B
raw_source_row_count = 593127
source_row_count = 11318
accepted_row_count = 11313
rejected_row_count = 5
coverage_scope = SEOUL_IT_FULL
region_scope = SEOUL
industry_scope = SOFTWARE_IT_SERVICE
```

- [ ] **Step 2: Run RED migration test**

Run: `./gradlew :company-service:test --tests '*WorkforceFlywayMigrationTest' --console=plain`

Expected: failure because the forward migration does not exist.

- [ ] **Step 3: Generate the forward migration**

Run the Task 3 CLI with the original ignored CSV as input and the exact new migration path as output. Before writing any SQL that includes money calculations, run the repository guard against the complete generated content; do not write if blocked.

- [ ] **Step 4: Verify forward-only and collision safety**

The migration must verify the pre-existing `2026-06` dataset is the known 4,247-row seed before replacing it. If the database contains an unknown independently imported dataset, raise an exception instead of deleting it. Delete old source and derived rows only after the precondition passes, insert the new cohort, rebuild, and mark the provenance record complete in one transaction.

- [ ] **Step 5: Run GREEN migration test and commit**

Run the Step 2 command and expect all assertions to pass.

Commit: `feat(company): seed complete Seoul IT workforce cohort`

---

### Task 5: Enforce dataset scope in reads and API copy

**Files:**
- Modify: `company-service/src/main/java/github/lms/lemuel/company/adapter/out/persistence/WorkforceComparisonPersistenceAdapter.java`
- Modify: `company-service/src/main/java/github/lms/lemuel/company/adapter/in/web/dto/WorkforceComparisonResponse.java`
- Modify: `company-service/src/test/java/github/lms/lemuel/company/adapter/out/persistence/WorkforceComparisonPersistenceAdaptersTest.java`
- Modify: `company-service/src/test/java/github/lms/lemuel/company/adapter/in/web/CompanyWorkforceControllerTest.java`

**Interfaces:**
- Changes: comparison statistics are readable only when `status='COMPLETE'` and `coverage_scope='SEOUL_IT_FULL'`.
- Changes: response note names the `2026-07-23` release and the Seoul software/IT-service population.

- [ ] **Step 1: Write failing query and response tests**

Require the real query contract to exclude missing/sample coverage and accept `SEOUL_IT_FULL`. Require the controller response note to contain `2026년 7월 23일 배포본` and `서울 소프트웨어·IT 서비스 사업장`.

- [ ] **Step 2: Run RED tests**

Run: `./gradlew :company-service:test --tests '*WorkforceComparisonPersistenceAdaptersTest' --tests '*CompanyWorkforceControllerTest' --console=plain`

Expected: failure because the query checks only `COMPLETE` and the note describes a general truncated population.

- [ ] **Step 3: Implement the scope gate and disclosure**

Add the coverage predicate to the build join and update the note without changing response field types or money serialization.

- [ ] **Step 4: Run GREEN tests and commit**

Run the Step 2 command and expect all tests to pass.

Commit: `fix(company): disclose Seoul IT comparison scope`

---

### Task 6: Full verification and release evidence

**Files:**
- Modify only files required by failures introduced by Tasks 1-5.

**Interfaces:**
- Produces fresh test, migration, data-scope, and Git-safety evidence.

- [ ] **Step 1: Verify generated artifact and ignored source**

Run:

```powershell
git check-ignore -v -- company-service/src/main/resources/국민연금공단_국민연금 가입 사업장 내역_20260723.csv
git ls-files -- "company-service/**/*.csv"
git diff 24c8dd209^ 24c8dd209 -- company-service/src/main/resources/db/migration/V20260804090000__seed_workforce_2026_06.sql
```

Confirm the NPS CSV remains ignored/untracked and the old migration commit remains intact.

- [ ] **Step 2: Run focused tests**

Run:

```powershell
python -m unittest scripts.etl.test_gen_workforce_seed -v
./gradlew :company-service:test --tests '*Workforce*' --tests '*NpsContributionRateTest' --tests '*NpsIncomeCapTest' --console=plain
```

- [ ] **Step 3: Run the complete company-service suite**

Run: `./gradlew :company-service:test --console=plain`

Expected: build succeeds with zero failed tests.

- [ ] **Step 4: Run repository guards and diff checks**

Run the guard on every changed company money/SQL file, `git diff --check`, inspect migration sizes and versions, and verify no absolute local username/path or CSV content/path was added to tracked files.

- [ ] **Step 5: Commit verification-only fixes if needed**

Commit only if verification required a code change: `test(company): verify Seoul IT workforce migration`.
