# economics-service Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 한국은행 ECOS 경제지표(기준금리·국고채3년·USD/KRW·CPI)를 수집·조회하는 신규 마이크로서비스 economics-service 를 추가한다.

**Architecture:** financial-statements-service(PR #134 로 develop 머지 완료)를 템플릿으로 한 "외부 공공 API 수집 + 자체 DB + 공개 read-only 조회" 헥사고날 서비스. 지표는 하드코딩하지 않고 DB 카탈로그(`indicators`)로 관리, 관측치는 `UNIQUE(indicator_code, observed_date)` upsert 로 SEED → ECOS 대체. shared-common 미의존(자체 최소 SecurityConfig).

**Tech Stack:** Java 25, Spring Boot 4.0.4, JPA+Flyway, PostgreSQL 17(자체 DB lemuel_economics, host 5440 — 5438·5439 는 compose 에서 이미 점유), Caffeine, SpringDoc, ArchUnit. port 8087(host) / 8080(컨테이너 내부 규약).

**Spec:** `docs/superpowers/specs/2026-07-06-economics-service-design.md`

**전제/주의:**
- 이 레포는 다른 세션에서 company-service·operation-service 등 추가 작업이 병행 중이다. **각 Task 시작 전 대상 파일의 현재 상태를 먼저 읽고**, 포트/볼륨/라우트 충돌은 그 시점의 docker-compose.yml 기준으로 재확인한다 (아래 포트 값은 2026-07-06 시점 확인값).
- "미러링" 지시 = 원본 파일을 복사한 뒤 치환표대로 바꾸고, 주석의 도메인 설명(DART/재무제표)을 ECOS/경제지표 문맥으로 고쳐 쓴다. financial-statements-service 파일은 읽기 전용 템플릿으로만 쓰고 절대 수정하지 않는다.
- 공통 치환표(이하 "표준 치환"): `financial→economics`(패키지·경로·설정 키), `Financial→Economics`, `FINANCIAL→ECONOMICS`, `/api/financial→/api/economics`, `/admin/financial→/admin/economics`, `lemuel_financial→lemuel_economics`, `5437→5440`(★ 5438·5439 는 compose 에서 이미 점유), `8086→8087`, `DART_API_KEY→ECOS_API_KEY`.
- 커밋은 Task 단위. 커밋 메시지 끝: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` (PowerShell 에서 한글 메시지는 `git commit -F <파일>` 사용).
- 검증 커맨드는 레포 루트에서 실행: `./gradlew :economics-service:compileJava`, `./gradlew :economics-service:test`.

## File Structure (전체 산출물 맵)

```
settings.gradle.kts                                  (수정: 모듈 1줄 추가)
economics-service/
├── build.gradle.kts                                 (미러: financial build.gradle.kts)
└── src/main/java/github/lms/lemuel/economics/
    ├── EconomicsApplication.java                    (미러)
    ├── config/SecurityConfig.java                   (미러)
    ├── config/AdminApiKeyFilter.java                (미러)
    ├── config/AsyncConfig.java                      (미러)
    ├── config/HttpClientConfig.java                 (미러 — Boot4 는 RestClient.Builder 빈 자동 제공 안 함)
    ├── config/CacheConfig.java                      (신규 — @EnableCaching)
    ├── domain/IndicatorCycle.java                   (신규)
    ├── domain/ValueSource.java                      (신규)
    ├── domain/Indicator.java                        (신규)
    ├── domain/IndicatorValue.java                   (신규 — 변동 계산 로직)
    ├── application/port/in/GetIndicatorsUseCase.java
    ├── application/port/in/GetIndicatorSeriesUseCase.java
    ├── application/port/in/SyncIndicatorsUseCase.java
    ├── application/port/in/SyncResult.java          (미러)
    ├── application/port/out/LoadIndicatorPort.java
    ├── application/port/out/LoadIndicatorValuePort.java
    ├── application/port/out/SaveIndicatorValuePort.java
    ├── application/port/out/EcosClientPort.java
    ├── application/service/IndicatorQueryService.java
    ├── application/service/EcosSyncService.java
    ├── adapter/in/web/IndicatorController.java
    ├── adapter/in/web/EconomicsSyncAdminController.java (미러)
    ├── adapter/in/web/SyncStatusTracker.java        (미러)
    ├── adapter/in/web/GlobalExceptionHandler.java   (미러)
    ├── adapter/out/persistence/IndicatorJpaEntity.java
    ├── adapter/out/persistence/IndicatorValueJpaEntity.java
    ├── adapter/out/persistence/IndicatorRepository.java
    ├── adapter/out/persistence/IndicatorValueRepository.java
    ├── adapter/out/persistence/IndicatorPersistenceAdapter.java
    └── adapter/out/external/EcosApiClient.java      (신규), EcosProperties.java (미러)
└── src/main/resources/
    ├── application.yml                              (미러 + app.economics 섹션)
    └── db/migration/V1__economics_core.sql          (신규)
    └── db/migration/V2__economics_seed.sql          (신규)
└── src/test/java/.../economics/
    ├── domain/IndicatorValueTest.java, IndicatorTest.java
    ├── application/service/EcosSyncServiceTest.java, IndicatorQueryServiceTest.java
    ├── adapter/in/web/IndicatorControllerTest.java
    └── architecture/HexagonalArchitectureTest.java  (미러)
docker-compose.yml                                   (수정: economics-postgres + economics-service + gateway env)
gateway-service/src/main/resources/application.yml   (수정: /api/economics/** 라우트)
frontend/src/api/economics.ts                        (신규)
frontend/src/pages/EconomicsPage.tsx                 (신규)
frontend/src/App.tsx(라우터)                          (수정: /economics 라우트 — financial 이 추가된 방식 미러)
CLAUDE.md, README.md                                 (수정: 서비스 목록 갱신)
```

---

## Chunk 1: 모듈 스캐폴딩 + 도메인 모델

### Task 1: Gradle 모듈 + 부팅 골격

**Files:**
- Modify: `settings.gradle.kts` (include 목록에 `"economics-service"` — `"financial-statements-service"` 다음 줄)
- Create: `economics-service/build.gradle.kts` ← 미러: `financial-statements-service/build.gradle.kts` (표준 치환; 상단 ★ 주석은 "ECOS 거시 경제지표를 제공하는 공개 read-only 조회 서비스. 자체 DB(lemuel_economics)…" 로 다시 씀. 의존성 목록은 동일 — Caffeine·Flyway·SpringDoc 전부 그대로)
- Create: `economics-service/src/main/java/github/lms/lemuel/economics/EconomicsApplication.java` ← 미러: `FinancialStatementsApplication.java` (클래스명 `EconomicsApplication`, javadoc 은 경제지표 문맥으로)
- Create: `economics-service/src/main/resources/application.yml` ← 미러: financial 의 application.yml (표준 치환: name `lemuel-economics`, url `jdbc:postgresql://localhost:5440/lemuel_economics?...`, pool-name `lemuel-economics-pool`, port 8087). `app:` 섹션은 아래로 교체:

```yaml
app:
  economics:
    # /admin/economics/** 게이트 공유 시크릿 — 미설정 시 통과+경고 (로컬 전용, 운영 필수)
    internal-api-key: ${ECONOMICS_INTERNAL_API_KEY:}
    ecos:
      # ecos.bok.or.kr 발급 인증키 — 미설정이면 수집 비활성(Flyway 시드 데이터로만 동작)
      api-key: ${ECOS_API_KEY:}
      base-url: https://ecos.bok.or.kr/api
    sync:
      request-interval-ms: 150   # ECOS 쿼터 보호용 호출 간격
```

- [ ] **Step 1:** settings.gradle.kts 수정 + 위 4개 파일 생성
- [ ] **Step 2:** 컴파일 확인 — Run: `./gradlew :economics-service:compileJava` → Expected: BUILD SUCCESSFUL
- [ ] **Step 3:** Commit — `feat(economics): economics-service 모듈 골격 — 독립 부팅 + 자체 DB 설정`

### Task 2: 도메인 모델 (TDD)

**Files:**
- Create: `domain/IndicatorCycle.java`, `domain/ValueSource.java`, `domain/Indicator.java`, `domain/IndicatorValue.java`
- Test: `src/test/java/github/lms/lemuel/economics/domain/IndicatorValueTest.java`, `IndicatorTest.java`

도메인은 순수 POJO(record). financial 의 Company/FinancialStatement 스타일(record + 생성자 검증 + 파생값 메서드) 준수. **금액/지표 값은 전부 BigDecimal** (@money-safety).

- [ ] **Step 1: 실패하는 테스트 작성** — `IndicatorValueTest`:

```java
package github.lms.lemuel.economics.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class IndicatorValueTest {

    private IndicatorValue value(String v, LocalDate d) {
        return new IndicatorValue(null, "BASE_RATE", d, new BigDecimal(v), ValueSource.SEED, null);
    }

    @Test
    void 전기_대비_변동폭과_변동률을_계산한다() {
        IndicatorValue prev = value("3.00", LocalDate.of(2026, 5, 1));
        IndicatorValue curr = value("3.25", LocalDate.of(2026, 6, 1));

        IndicatorValue.Change change = curr.changeFrom(prev);

        assertThat(change.amount()).isEqualByComparingTo("0.25");
        assertThat(change.ratePercent()).isEqualByComparingTo("8.3333"); // 0.25/3.00*100, scale 4 HALF_UP
    }

    @Test
    void 이전값이_null_이면_변동은_null() {
        assertThat(value("3.25", LocalDate.of(2026, 6, 1)).changeFrom(null)).isNull();
    }

    @Test
    void 이전값이_0_이면_변동률은_null_변동폭은_계산() {
        IndicatorValue.Change change = value("1.00", LocalDate.of(2026, 6, 1))
                .changeFrom(value("0.00", LocalDate.of(2026, 5, 1)));
        assertThat(change.amount()).isEqualByComparingTo("1.00");
        assertThat(change.ratePercent()).isNull();
    }

    @Test
    void 값이_null_이면_생성_거부() {
        assertThatThrownBy(() -> new IndicatorValue(null, "BASE_RATE",
                LocalDate.of(2026, 6, 1), null, ValueSource.ECOS, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`IndicatorTest`: code/name/ecosStatCode blank 거부, cycle null 거부 정도의 생성자 검증 테스트 3~4개.

- [ ] **Step 2:** Run: `./gradlew :economics-service:test --tests "*.domain.*"` → Expected: 컴파일 실패(클래스 없음)
- [ ] **Step 3: 최소 구현**

```java
// IndicatorCycle.java
package github.lms.lemuel.economics.domain;

/** 지표 관측 주기 — ECOS StatisticSearch 의 cycle 파라미터와 1:1 (D=일별 YYYYMMDD, M=월별 YYYYMM). */
public enum IndicatorCycle { D, M }
```

```java
// ValueSource.java
package github.lms.lemuel.economics.domain;

/** 관측치 출처 — SEED(Flyway 근사 샘플) / ECOS(한국은행 실데이터, upsert 로 SEED 를 덮어씀). */
public enum ValueSource { SEED, ECOS }
```

```java
// Indicator.java
package github.lms.lemuel.economics.domain;

import java.time.Instant;

/** 경제지표 카탈로그 항목 — 지표 추가는 스키마 변경 없이 indicators row 추가로 끝난다. */
public record Indicator(String code, String name, String unit, IndicatorCycle cycle,
                        String ecosStatCode, String ecosItemCode, Instant updatedAt) {

    public Indicator {
        requireText(code, "code");
        requireText(name, "name");
        requireText(unit, "unit");
        requireText(ecosStatCode, "ecosStatCode");
        requireText(ecosItemCode, "ecosItemCode");
        if (cycle == null) {
            throw new IllegalArgumentException("cycle 은 필수입니다");
        }
    }

    private static void requireText(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " 은(는) 필수입니다");
        }
    }
}
```

```java
// IndicatorValue.java
package github.lms.lemuel.economics.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/** 지표 관측치 1건. 파생값(전기 대비 변동)은 저장하지 않고 여기서 계산한다. */
public record IndicatorValue(Long id, String indicatorCode, LocalDate observedDate,
                             BigDecimal value, ValueSource source, Instant syncedAt) {

    public IndicatorValue {
        if (indicatorCode == null || indicatorCode.isBlank()) {
            throw new IllegalArgumentException("indicatorCode 은(는) 필수입니다");
        }
        if (observedDate == null || value == null || source == null) {
            throw new IllegalArgumentException("observedDate/value/source 는 필수입니다");
        }
    }

    /** 변동폭(amount)과 변동률 %(ratePercent, scale 4 HALF_UP). 분모 0 이면 변동률만 null. */
    public record Change(BigDecimal amount, BigDecimal ratePercent) { }

    public Change changeFrom(IndicatorValue previous) {
        if (previous == null) {
            return null;
        }
        BigDecimal amount = value.subtract(previous.value);
        BigDecimal ratePercent = previous.value.signum() == 0
                ? null
                : amount.multiply(BigDecimal.valueOf(100))
                        .divide(previous.value, 4, RoundingMode.HALF_UP);
        return new Change(amount, ratePercent);
    }
}
```

- [ ] **Step 4:** Run: `./gradlew :economics-service:test --tests "*.domain.*"` → Expected: 전부 PASS
- [ ] **Step 5:** Commit — `feat(economics): 지표 카탈로그·관측치 도메인 모델 + 변동 계산`

---

## Chunk 2: Flyway + 영속성

### Task 3: Flyway V1(스키마+카탈로그) / V2(시드 관측치)

**Files:**
- Create: `economics-service/src/main/resources/db/migration/V1__economics_core.sql`
- Create: `economics-service/src/main/resources/db/migration/V2__economics_seed.sql`

- [ ] **Step 1:** V1 작성 (financial V1 의 주석 스타일 준수 — TIMESTAMPTZ 는 ddl-auto=validate 통과 조건):

```sql
-- V1: 경제지표 카탈로그 + 관측치 (economics-service 자체 DB)
--
-- indicators       : 지표 정의 카탈로그. 지표 추가 = row 추가 (스키마 변경 없음).
-- indicator_values : 관측치 시계열. (지표, 관측일) UNIQUE upsert 로 SEED → ECOS 대체.
--                    월별(M) 지표의 observed_date 는 해당 월 1일로 정규화해 저장.

CREATE TABLE IF NOT EXISTS indicators (
    code           VARCHAR(30)  PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    unit           VARCHAR(20)  NOT NULL,
    cycle          VARCHAR(1)   NOT NULL,           -- D 일별 / M 월별 (ECOS cycle 과 1:1)
    ecos_stat_code VARCHAR(20)  NOT NULL,
    ecos_item_code VARCHAR(20)  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_indicator_cycle CHECK (cycle IN ('D', 'M'))
);

CREATE TABLE IF NOT EXISTS indicator_values (
    id             BIGSERIAL     PRIMARY KEY,
    indicator_code VARCHAR(30)   NOT NULL REFERENCES indicators (code),
    observed_date  DATE          NOT NULL,
    value          NUMERIC(18,4) NOT NULL,
    source         VARCHAR(10)   NOT NULL,          -- SEED(근사 샘플) / ECOS(실데이터)
    synced_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_iv_indicator_date UNIQUE (indicator_code, observed_date),
    CONSTRAINT chk_iv_source CHECK (source IN ('SEED', 'ECOS'))
);

CREATE INDEX IF NOT EXISTS idx_iv_code_date ON indicator_values (indicator_code, observed_date DESC);

-- 초기 카탈로그. ECOS 통계/항목 코드가 틀렸다면 이 row 만 고치면 된다 (코드 수정 불필요).
INSERT INTO indicators (code, name, unit, cycle, ecos_stat_code, ecos_item_code) VALUES
    ('BASE_RATE',   '한국은행 기준금리', '%',    'D', '722Y001', '0101000'),
    ('TREASURY_3Y', '국고채 3년 금리',   '%',    'D', '817Y002', '010200000'),
    ('USD_KRW',     '원/달러 환율',      'KRW',  'D', '731Y001', '0000001'),
    ('CPI',         '소비자물가지수',    '2020=100', 'M', '901Y009', '0')
ON CONFLICT (code) DO NOTHING;
```

주의: 설계 스펙에는 BASE_RATE 가 M 로 적혀 있으나 ECOS 722Y001 은 **일별(D)** 통계라 D 로 정정한다 (스펙의 "카탈로그는 데이터라 조정 쉬움" 원칙 범위 내).

- [ ] **Step 2:** V2 작성 — 시드 관측치, 전부 `source='SEED'`:
  - `BASE_RATE`: 2025-07 ~ 2026-06 사이 월초 12건 (예: 3.50 → 3.25 → 3.00 수준의 근사 단계값)
  - `TREASURY_3Y`, `USD_KRW`: 최근 60영업일 근사 — 60줄 하드코딩 대신 `generate_series` 사용:

```sql
-- V2: 시드 관측치 (근사치, source='SEED' — ECOS 동기화가 UNIQUE upsert 로 대체)

-- 국고채 3년: 최근 90일 중 주말 제외, 2.60% 주변 소폭 변동(결정적 — random() 금지)
INSERT INTO indicator_values (indicator_code, observed_date, value, source)
SELECT 'TREASURY_3Y', d::date,
       2.60 + (EXTRACT(DAY FROM d)::int % 7) * 0.01,
       'SEED'
FROM generate_series(CURRENT_DATE - INTERVAL '90 days', CURRENT_DATE, '1 day') AS d
WHERE EXTRACT(ISODOW FROM d) < 6
ON CONFLICT (indicator_code, observed_date) DO NOTHING;

-- USD_KRW: 동일 패턴, 1380 주변 ±7원
INSERT INTO indicator_values (indicator_code, observed_date, value, source)
SELECT 'USD_KRW', d::date,
       1380 + (EXTRACT(DAY FROM d)::int % 15) - 7,
       'SEED'
FROM generate_series(CURRENT_DATE - INTERVAL '90 days', CURRENT_DATE, '1 day') AS d
WHERE EXTRACT(ISODOW FROM d) < 6
ON CONFLICT (indicator_code, observed_date) DO NOTHING;

-- BASE_RATE / CPI: 월초 정규화 24개월 (같은 generate_series 패턴, '1 month' 간격,
--                  date_trunc('month', d)::date, CPI 는 110 에서 월 +0.2 선형 근사)
-- (BASE_RATE 는 최근 24개월 월초, 3.50 에서 6개월마다 -0.25 단계 근사 — CASE 식으로 결정적으로)
```

(V2 의 BASE_RATE/CPI 블록도 위 두 블록과 같은 형태로 완성해서 넣는다 — 값은 근사면 충분하되 **결정적**이어야 한다. `random()` 사용 금지.)

- [ ] **Step 3:** 로컬 검증 (DB 컨테이너가 없으므로 이 시점엔 문법 검토만; 부팅 검증은 Task 12 에서) — SQL 리뷰 후 그대로 진행
- [ ] **Step 4:** Commit — `feat(economics): Flyway V1 스키마+지표 카탈로그, V2 시드 관측치`

### Task 4: JPA 엔티티 + 리포지토리 + 영속성 어댑터

**Files:**
- Create: `adapter/out/persistence/IndicatorJpaEntity.java`, `IndicatorValueJpaEntity.java`, `IndicatorRepository.java`, `IndicatorValueRepository.java`, `IndicatorPersistenceAdapter.java`
- Create: `application/port/out/LoadIndicatorPort.java`, `LoadIndicatorValuePort.java`, `SaveIndicatorValuePort.java`

financial 의 `CompanyJpaEntity`/`CompanyPersistenceAdapter`/`CompanyRepository` 를 열어 스타일(엔티티↔도메인 변환 메서드 위치, upsert 방식)을 그대로 따른다.

- [ ] **Step 1:** 포트 인터페이스 3개:

```java
// LoadIndicatorPort.java
package github.lms.lemuel.economics.application.port.out;

import github.lms.lemuel.economics.domain.Indicator;

import java.util.List;
import java.util.Optional;

public interface LoadIndicatorPort {
    List<Indicator> findAll();
    Optional<Indicator> findByCode(String code);
}
```

```java
// LoadIndicatorValuePort.java
package github.lms.lemuel.economics.application.port.out;

import github.lms.lemuel.economics.domain.IndicatorValue;

import java.time.LocalDate;
import java.util.List;

public interface LoadIndicatorValuePort {
    /** 최신 관측치부터 limit 건 (observedDate DESC). 변동 계산엔 limit=2. */
    List<IndicatorValue> findLatest(String indicatorCode, int limit);
    /** [from, to] 시계열, observedDate ASC. */
    List<IndicatorValue> findSeries(String indicatorCode, LocalDate from, LocalDate to);
}
```

```java
// SaveIndicatorValuePort.java
package github.lms.lemuel.economics.application.port.out;

import github.lms.lemuel.economics.domain.IndicatorValue;

public interface SaveIndicatorValuePort {
    /** (indicator_code, observed_date) UNIQUE upsert — SEED 를 ECOS 가 덮어쓴다. */
    void upsert(IndicatorValue value);
}
```

- [ ] **Step 2:** 엔티티 2개 — `@Table(name="indicators")` / `@Table(name="indicator_values")`, 컬럼은 V1 과 1:1 (`updated_at`/`synced_at` 은 Instant). `IndicatorValueJpaEntity.value` 는 `@Column(precision=18, scale=4) BigDecimal`. 각 엔티티에 `toDomain()` / `static fromDomain(...)`.
- [ ] **Step 3:** 리포지토리 — `IndicatorRepository extends JpaRepository<IndicatorJpaEntity, String>`. `IndicatorValueRepository extends JpaRepository<IndicatorValueJpaEntity, Long>` + 메서드:

```java
List<IndicatorValueJpaEntity> findByIndicatorCodeOrderByObservedDateDesc(String indicatorCode, Limit limit);
List<IndicatorValueJpaEntity> findByIndicatorCodeAndObservedDateBetweenOrderByObservedDateAsc(
        String indicatorCode, LocalDate from, LocalDate to);
Optional<IndicatorValueJpaEntity> findByIndicatorCodeAndObservedDate(String indicatorCode, LocalDate observedDate);
```

- [ ] **Step 4:** `IndicatorPersistenceAdapter` — 세 포트 모두 구현하는 단일 `@Component`. upsert 는 financial 어댑터와 같은 방식: `findByIndicatorCodeAndObservedDate` 조회 후 있으면 값/source/syncedAt 갱신, 없으면 insert (`@Transactional`).
- [ ] **Step 5:** Run: `./gradlew :economics-service:compileJava` → Expected: BUILD SUCCESSFUL
- [ ] **Step 6:** Commit — `feat(economics): 영속성 어댑터 — 카탈로그 조회 + 관측치 upsert`

---

## Chunk 3: ECOS 클라이언트 + 동기화 배치

### Task 5: EcosClientPort + EcosApiClient

**Files:**
- Create: `application/port/out/EcosClientPort.java`
- Create: `adapter/out/external/EcosApiClient.java`, `adapter/out/external/EcosProperties.java`
- Create: `config/HttpClientConfig.java`

- [ ] **Step 1:** 포트:

```java
// EcosClientPort.java
package github.lms.lemuel.economics.application.port.out;

import github.lms.lemuel.economics.domain.Indicator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface EcosClientPort {

    boolean isConfigured();

    /** 지표의 [from, to] 관측치 조회. 결측/휴장일은 응답에 없을 뿐 — 에러 아님. */
    List<Observation> fetchObservations(Indicator indicator, LocalDate from, LocalDate to);

    record Observation(LocalDate observedDate, BigDecimal value) { }
}
```

- [ ] **Step 2:** `EcosProperties` — financial 의 `DartProperties` 미러 (`@ConfigurationProperties("app.economics.ecos")`, apiKey/baseUrl). 그리고 `config/HttpClientConfig.java` ← 미러: financial `HttpClientConfig.java` (★ Boot4 는 `RestClient.Builder` 빈을 자동 제공하지 않는다 — 이 미러를 빠뜨리면 EcosApiClient 주입 실패로 부팅이 죽는다).
- [ ] **Step 3:** `EcosApiClient implements EcosClientPort` — financial 의 `DartApiClient` 를 열어 RestClient 사용 방식을 따르되, ECOS 계약은:
  - URL: `{baseUrl}/StatisticSearch/{apiKey}/json/kr/1/10000/{statCode}/{cycle}/{start}/{end}/{itemCode}`
  - 날짜 포맷: cycle D → `yyyyMMdd`, M → `yyyyMM` (응답 TIME 도 동일 포맷; M 은 파싱 후 월 1일로 정규화)
  - 정상 응답: `{"StatisticSearch": {"list_total_count": n, "row": [{"TIME": "...", "DATA_VALUE": "..."}]}}`
  - 오류 응답: HTTP 200 에 `{"RESULT": {"CODE": "INFO-200", "MESSAGE": "..."}}` — **INFO-200(데이터 없음)은 빈 리스트**, 그 외 CODE 는 예외
  - `DATA_VALUE` 가 빈 문자열/`"-"` 인 row 는 skip
  - 페이지네이션은 YAGNI: 요청 상한 10000건 > 일별 1년치(≈250건)라 1콜로 충분 — 주석으로 명시
- [ ] **Step 4:** Run: `./gradlew :economics-service:compileJava` → Expected: BUILD SUCCESSFUL
- [ ] **Step 5:** Commit — `feat(economics): ECOS StatisticSearch 클라이언트`

### Task 6: EcosSyncService (TDD)

**Files:**
- Create: `application/port/in/SyncIndicatorsUseCase.java`, `application/port/in/SyncResult.java` (financial `SyncResult` 미러 — record(scanned, upserted, skipped, failed))
- Create: `application/service/EcosSyncService.java`
- Test: `application/service/EcosSyncServiceTest.java`

```java
// SyncIndicatorsUseCase.java
package github.lms.lemuel.economics.application.port.in;

import java.time.LocalDate;

public interface SyncIndicatorsUseCase {
    /** indicatorCode=null 이면 카탈로그 전체. [from, to] 관측치를 ECOS 에서 받아 upsert. */
    SyncResult syncIndicators(String indicatorCode, LocalDate from, LocalDate to);
}
```

- [ ] **Step 1: 실패하는 테스트** — Mockito 로 `EcosClientPort`/`LoadIndicatorPort`/`SaveIndicatorValuePort` 모킹 (financial 의 `DartSyncServiceTest` 스타일). 케이스:
  - 전체 동기화: 카탈로그 4개 지표 × 관측치 upsert 집계 확인
  - 특정 code 동기화: 그 지표만 fetch
  - 존재하지 않는 code → `IllegalArgumentException`
  - 한 지표 fetch 가 RuntimeException → failed 집계 + 나머지 지표 계속 진행
  - `isConfigured()==false` → `IllegalStateException` ("ECOS API 키가 설정되지 않았습니다")
  - from > to → `IllegalArgumentException`
- [ ] **Step 2:** Run: `./gradlew :economics-service:test --tests "*EcosSyncServiceTest"` → Expected: 컴파일 실패
- [ ] **Step 3: 구현** — `DartSyncService` 구조 미러: 지표 루프, 지표별 try-catch 로 실패 집계 후 계속, `requestIntervalMs` pause, PROGRESS_LOG_INTERVAL 로그. 관측치는 `IndicatorValue(..., ValueSource.ECOS, null)` 로 만들어 upsert. SyncResult: scanned=지표 수, upserted=관측치 upsert 수, skipped=0건 응답 지표 수, failed=예외 지표 수.
- [ ] **Step 4:** Run: `./gradlew :economics-service:test --tests "*EcosSyncServiceTest"` → Expected: PASS
- [ ] **Step 5:** Commit — `feat(economics): ECOS 동기화 배치 — 지표별 실패 격리 + 쿼터 보호`

### Task 7: 관리자 트리거 (202 + 상태조회)

**Files:**
- Create: `config/AsyncConfig.java` ← 미러: financial `AsyncConfig.java` (executor 빈 이름 `syncTaskExecutor` 유지)
- Create: `adapter/in/web/SyncStatusTracker.java` ← 미러: financial `SyncStatusTracker.java` (같은 패키지 위치 — financial 도 in/web 에 둠)
- Create: `adapter/in/web/EconomicsSyncAdminController.java` ← 미러: `FinancialSyncAdminController.java`, 엔드포인트만 교체:

```java
@RestController
@RequestMapping("/admin/economics/sync")
public class EconomicsSyncAdminController {
    // 생성자·submit()·status() 는 financial 미러 (UseCase 1개뿐이라 필드는 SyncIndicatorsUseCase 하나)

    @PostMapping
    public ResponseEntity<Map<String, String>> sync(
            @RequestParam(required = false) String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String job = (code == null ? "all" : code) + ":" + from + "~" + to;
        return submit(job, () -> syncIndicatorsUseCase.syncIndicators(code, from, to));
    }
}
```

- [ ] **Step 1:** 3개 파일 생성 (statusUrl 문자열은 `/admin/economics/sync/status` 로)
- [ ] **Step 2:** Run: `./gradlew :economics-service:compileJava` → Expected: BUILD SUCCESSFUL
- [ ] **Step 3:** Commit — `feat(economics): 동기화 관리자 트리거 — 202+상태조회, 동시 실행 409`

---

## Chunk 4: 조회 API + 보안

### Task 8: IndicatorQueryService (TDD)

**Files:**
- Create: `application/port/in/GetIndicatorsUseCase.java`, `GetIndicatorSeriesUseCase.java`
- Create: `application/service/IndicatorQueryService.java`, `config/CacheConfig.java`
- Test: `application/service/IndicatorQueryServiceTest.java`

```java
// GetIndicatorsUseCase.java
package github.lms.lemuel.economics.application.port.in;

import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorValue;

import java.util.List;

public interface GetIndicatorsUseCase {

    /** 카탈로그 전체 + 각 지표의 최신 관측치/전기 대비 변동(관측치 부족 시 null). */
    List<IndicatorSnapshot> getIndicators();

    IndicatorSnapshot getIndicator(String code);

    record IndicatorSnapshot(Indicator indicator, IndicatorValue latest, IndicatorValue.Change change) { }
}
```

```java
// GetIndicatorSeriesUseCase.java
package github.lms.lemuel.economics.application.port.in;

import github.lms.lemuel.economics.domain.IndicatorValue;

import java.time.LocalDate;
import java.util.List;

public interface GetIndicatorSeriesUseCase {
    /** from/to null 이면 최근 1년. observedDate ASC. */
    List<IndicatorValue> getSeries(String code, LocalDate from, LocalDate to);
}
```

- [ ] **Step 1: 실패하는 테스트** — 케이스:
  - `getIndicators()`: 지표별 `findLatest(code, 2)` 결과로 latest+change 조립 (지표 4개 정도라 N+1 허용 — 테스트 주석에 명시)
  - 관측치 0건 지표: latest/change 모두 null 인 snapshot
  - 관측치 1건: latest 있고 change null
  - `getIndicator("없는코드")` → `IndicatorNotFoundException` (신규 예외 — `application.port.in` 이 아니라 `domain` 에 두면 ArchUnit 위반 없음; financial 의 NotFound 예외 위치를 확인해 동일하게)
  - `getSeries` from>to → `IllegalArgumentException`; from/to null → 최근 1년으로 보정해 포트 호출
- [ ] **Step 2:** Run → Expected: 컴파일 실패
- [ ] **Step 3:** 구현 (`@Transactional(readOnly = true)`, `@Cacheable` — 카탈로그 스냅샷 `"indicatorSnapshots"`, 시계열 `"indicatorSeries"` key=`code+from+to`. ★ `@Cacheable` 이 동작하려면 `@EnableCaching` 필요 — `config/CacheConfig.java` 신규 (`@Configuration @EnableCaching public class CacheConfig { }` — Caffeine 스펙은 application.yml `spring.cache.caffeine.spec` 이 담당). **sync upsert 후 캐시 정합**: `EcosSyncService` 완료 시 `@CacheEvict(cacheNames = {"indicatorSnapshots", "indicatorSeries"}, allEntries = true)` — 캐시 TTL 600s 만 믿지 말 것)
- [ ] **Step 4:** Run → Expected: PASS
- [ ] **Step 5:** Commit — `feat(economics): 지표 조회 서비스 — 최신값+변동, 시계열, 캐시`

### Task 9: IndicatorController + 예외 처리 (MockMvc)

**Files:**
- Create: `adapter/in/web/IndicatorController.java`, `adapter/in/web/GlobalExceptionHandler.java` (financial 미러 + `IndicatorNotFoundException → 404` 매핑, `IllegalArgumentException → 400`)
- Test: `adapter/in/web/IndicatorControllerTest.java` (financial `CompanyControllerTest` 의 standalone MockMvc 스타일)

응답 DTO 는 컨트롤러 내부 record 로 (financial 스타일 확인 후 동일하게):

```
GET /api/economics/indicators
→ [{code, name, unit, cycle, latest: {observedDate, value} | null,
    change: {amount, ratePercent} | null}]
GET /api/economics/indicators/{code}/latest        → 단건 (위 요소와 동일 구조)
GET /api/economics/indicators/{code}/series?from=&to=
→ {code, name, unit, points: [{observedDate, value, source}]}
```

- [ ] **Step 1: 실패하는 테스트** — 목록 200, 단건 200, 없는 code 404, series 200(기본 1년), from>to 400
- [ ] **Step 2:** Run → Expected: FAIL
- [ ] **Step 3:** 컨트롤러 구현 (`@DateTimeFormat(iso = DATE)` 파라미터)
- [ ] **Step 4:** Run: `./gradlew :economics-service:test` → Expected: 전부 PASS
- [ ] **Step 5:** Commit — `feat(economics): 공개 조회 API — 카탈로그/최신값/시계열`

### Task 10: SecurityConfig + AdminApiKeyFilter

**Files:**
- Create: `config/SecurityConfig.java` ← 미러: financial `SecurityConfig.java` (permitAll GET `/api/economics/**`, admin `/admin/economics/**`, 나머지 denyAll — CORS 목록 동일)
- Create: `config/AdminApiKeyFilter.java` ← 미러: financial `AdminApiKeyFilter.java` (`app.economics.internal-api-key`, URI prefix `/admin/economics/`)

- [ ] **Step 1:** 2개 파일 생성
- [ ] **Step 2:** Run: `./gradlew :economics-service:test` → Expected: PASS (컨트롤러 테스트는 standalone 이라 보안 영향 없음)
- [ ] **Step 3:** Commit — `feat(economics): 자체 최소 보안 — GET 공개, admin 은 X-Internal-Api-Key 게이트`

---

## Chunk 5: 아키텍처 테스트 + 인프라 + 프론트 + 문서

### Task 11: ArchUnit 헥사고날 검증

**Files:**
- Create: `src/test/java/github/lms/lemuel/economics/architecture/HexagonalArchitectureTest.java` ← 미러: financial `HexagonalArchitectureTest.java` (패키지 루트만 `github.lms.lemuel.economics` 로)

- [ ] **Step 1:** 파일 생성 → Run: `./gradlew :economics-service:test --tests "*HexagonalArchitectureTest"` → Expected: PASS (위반 시 해당 코드 수정이 우선, 룰 완화 금지)
- [ ] **Step 2:** Commit — `test(economics): ArchUnit 헥사고날 의존 방향 검증`

### Task 12: docker-compose + gateway 라우팅 + 부팅 검증

**Files:**
- Modify: `docker-compose.yml` — `financial-postgres`/`financial-statements-service` 블록을 미러해 `economics-postgres`(host 매핑 `127.0.0.1:5440:5432` — ★ 작업 시점에 5440 도 점유됐으면 다음 빈 포트로, `POSTGRES_DB=lemuel_economics`, volume `economics-postgres-data`) + `economics-service`(★ 컨테이너 내부는 다른 서비스처럼 `SERVER_PORT: 8080`/`MANAGEMENT_SERVER_PORT: 8080`, host 매핑 `127.0.0.1:8087:8080`, `MODULE=economics-service`, `depends_on: economics-postgres`, datasource url 은 `economics-postgres:5432`) 추가. 기존 블록은 건드리지 말 것.
- Modify: `docker-compose.yml` gateway 서비스 env — `ECONOMICS_SERVICE_URI: http://economics-service:8080` (★ 기존 URI 전부 컨테이너 내부 포트 8080 규약 — 8087 아님)
- Modify: `gateway-service/src/main/resources/application.yml` — financial-statements-service 라우트 형식 그대로:

```yaml
            - id: economics-service
              uri: ${ECONOMICS_SERVICE_URI:http://localhost:8087}
              # 공개 조회 API 만 라우팅 — 수집 트리거(/admin/economics/**)는 외부 미노출
              predicates:
                - Path=/api/economics/**
```

- [ ] **Step 1:** 위 수정 적용
- [ ] **Step 2: 로컬 부팅 검증** — Run: `docker compose up -d economics-postgres` 후 `./gradlew :economics-service:bootRun` (application.yml 이 localhost:5440 을 봄)
  - Expected: Flyway V1/V2 적용 로그, 부팅 성공
  - `curl http://127.0.0.1:8087/api/economics/indicators` → 200, 시드 기반 4개 지표+최신값 JSON (localhost 대신 **127.0.0.1** — IPv6 함정)
  - `curl -X POST "http://127.0.0.1:8087/admin/economics/sync?from=2026-01-01&to=2026-06-30"` → `ECOS_API_KEY` 미설정이면 상태조회에서 "ECOS API 키가 설정되지 않았습니다" fail 확인 (게이트 필터는 키 미설정 시 통과+경고 동작 확인)
- [ ] **Step 3:** bootRun 종료, Commit — `feat(economics): compose economics-postgres/economics-service + gateway /api/economics 라우팅`

### Task 13: 프론트 — /economics 페이지

**Files:**
- Create: `frontend/src/api/economics.ts` — 기존 `frontend/src/api/` 의 다른 파일(예: `financial.ts` 있으면 그것, 없으면 `settlement.ts`)의 axios 인스턴스/타입 스타일 미러. 함수: `fetchIndicators()`, `fetchIndicatorSeries(code, from?, to?)`
- Create: `frontend/src/pages/EconomicsPage.tsx` — 지표 카드 그리드(이름·최신값·단위·전기 대비 변동, 변동 부호에 따라 상승/하락 표기) + 카드 클릭 시 시계열 섹션(기존 프로젝트에 차트 라이브러리가 이미 있으면 그걸로 라인 차트, **없으면 새 의존성 추가 금지** — 단순 테이블로. 먼저 `frontend/package.json` 확인)
- Modify: 라우터 파일 — `/economics` 공개 라우트 (financial 페이지가 추가돼 있으면 같은 방식, 메뉴 노출 위치도 동일하게)

- [ ] **Step 1:** `frontend/package.json`·라우터·기존 api 파일 확인 후 2개 파일 생성 + 라우트 추가
- [ ] **Step 2:** Run: `cd frontend && npm run build` → Expected: 빌드 성공 (dist 산출물은 커밋하지 않음 — .gitignore 정책 확인)
- [ ] **Step 3:** Commit — `feat(economics): 프론트 /economics 경제지표 조회 페이지`

### Task 14: 문서 갱신 + 전체 검증

**Files:**
- Modify: `CLAUDE.md` — 모듈 구조/서비스별 책임에 economics-service 추가 (financial 행 형식 미러: "ECOS 경제지표 공개 조회 — 수집 배치, 자체 DB lemuel_economics, shared-common 미의존"). 마이크로서비스 개수 문구는 **작업 시점의 CLAUDE.md 현재 문구를 보고 정확히** (company/operation 등 병행 작업으로 숫자가 움직이는 중).
- Modify: `README.md` — 서비스 목록에 1줄.

- [ ] **Step 1:** 문서 수정
- [ ] **Step 2: 최종 전체 검증** — Run: `./gradlew :economics-service:test :gateway-service:compileJava` → Expected: PASS / SUCCESSFUL (전체 `./gradlew build` 는 다른 서비스 테스트까지 돌아 오래 걸림 — 변경 범위 밖 실패가 나면 이 작업과 무관한지 확인만)
- [ ] **Step 3:** Commit — `docs: economics-service 반영 — 서비스 목록/모듈 구조`
