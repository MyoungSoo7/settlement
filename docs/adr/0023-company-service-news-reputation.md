# ADR 0023 — company-service 도입 (기업 뉴스·평판 조회 서비스)

- 상태: Accepted (Phase 1 구현 중)
- 일자: 2026-07-06

## 컨텍스트

플랫폼에 특정 기업에 대한 **뉴스 기사와 평판**을 조회하는 기능이 필요하다. 단순 조회 기능으로
두면 기존 도메인과 붕 뜨지만, 이 플랫폼에는 명확한 연결 고리가 있다:

> 셀러(법인)의 평판 악화 신호 → loan-service 선정산 대출 심사·한도 반영,
> settlement-service 홀드백 정책 참고 지표.

즉 뉴스·공시라는 **비정형 데이터**가 Kafka 이벤트로 여신·정산이라는 **돈의 흐름**에 영향을 주는
구조를 목표로 한다.

한편 직전에 도입된 `financial-statements-service`(ADR 미작성, 2026-07-06 설계 문서 참조)가 이미
"기업(Company)" 마스터를 소유하고 DART 재무제표를 다룬다. 신규 서비스와의 경계 정리가 필요했다.

## 결정

### 1. 별도 마이크로서비스 `company-service` 신설 (financial 과 분리 유지)

두 서비스 모두 기업을 다루지만 Bounded Context 가 다르다:

| 관심사 | financial-statements-service | company-service |
|---|---|---|
| 데이터 성격 | **정형** — DART 재무제표 수치 | **비정형** — 뉴스 기사, 평판, 이슈 |
| 변경 주기 | 분기/연 단위 | 실시간~일 단위 |
| 저장소 | PostgreSQL | PostgreSQL (+Phase 2 에서 ES 기사 검색) |

기업 식별자는 **`stockCode`(종목코드 6자리) + `corpCode`(DART 8자리)를 공용 비즈니스 키로 표준화**
한다. 기업 마스터는 서로 프로젝션하지 않고 각자 소유한다(초기엔 중복 허용이 단순하다 — 시드도
동일 종목코드 세트를 쓴다). 마스터 단일화가 필요해지면 company-service 를 오너로 승격하고
`lemuel.company.synced` 이벤트로 financial 이 프로젝션하는 후속 ADR 로 푼다.

### 2. financial 과 동일한 standalone 패턴 (shared-common 미의존, Phase 1 한정)

- 베이스 패키지 `github.lms.lemuel.company` 로 스캔 한정, 자체 최소 SecurityConfig.
- 조회(GET `/api/company/**`)는 공개 데이터(뉴스 메타데이터)라 무인증.
- 유일한 쓰기 경로인 수집 트리거(`/admin/company/**`)는 `X-Internal-Api-Key` 게이트
  (financial 의 `AdminApiKeyFilter` 와 동일 시맨틱).
- 자체 DB `lemuel_company`(DB-per-service, 호스트 5438), port 8090.
- gateway 미라우팅 — financial 과 동일한 직접 노출 방식. 인증 사용자 기능(관심기업 구독 등)이
  들어오는 시점에 gateway 라우트 + JWT 를 붙인다.
- **Phase 3(이벤트 발행)에서 shared-common(Outbox·Kafka) 의존을 추가**한다 — 그 전까지는
  이벤트가 없으므로 죽은 무게를 물지 않는다.

### 3. 기사 수집 — 저작권·멱등 원칙

- **기사 본문 전문을 저장하지 않는다.** 제목·요약(발췌)·언론사·발행일시·원문 URL 만 저장 —
  저작권 리스크 회피가 설계 제약이다.
- 멱등 키: `articles.url_hash`(원문 URL 정규화 후 SHA-256) UNIQUE — 재수집·중복 수집 방어.
  outbox 3단 멱등 방어와 같은 철학의 1차 방어선.
- 수집원 Phase 1: 네이버 뉴스 검색 API(기업명 쿼리). 미설정 시 수집 비활성(DART_API_KEY 미설정 시
  financial 과 동일 시맨틱). Phase 2+ 에서 DART 공시·RSS 확장.
- 수집은 장시간 배치라 202 + 백그라운드 실행 + 인메모리 상태 보드(동시 1건, financial
  `SyncStatusTracker` 패턴), 외부 API 쿼터 보호용 호출 간격 설정.

### 4. 평판 스코어 — INSERT-only 스냅샷 (Phase 2)

`reputation_scores` 는 기업별 일 단위 스냅샷을 **UPDATE 없이 INSERT-only** 로 쌓는다 —
`commission_rate` 스냅샷과 동일한 이력 보존 원칙. 점수 산식이 바뀌어도 과거 스냅샷은 불변이라
"그 시점에 왜 대출이 거절됐나"를 재현할 수 있다(여신 연계 시 감사 요건). 산식 v1 은 룰 기반
키워드 분류(`FINANCIAL/LEGAL/LABOR/PRODUCT/GOVERNANCE`)로 시작하고, 감성분석은
`AnalyzeSentimentPort` 뒤에 숨겨 LLM 구현체로 무중단 교체 가능하게 한다.

### 5. 이벤트 연계 (Phase 3 — 구현 완료)

company 가 shared-common 의 Outbox·멱등 인프라를 물어(단, `common.outbox` 만 스캔 —
JWT/audit 스택은 제외해 자체 SecurityConfig 유지) 평판 등급 변동을 Kafka 로 발행하고,
loan 이 이벤트 드리븐 프로젝션으로 소비한다.

```
[company-service] ReputationSnapshotWriter (한 DB tx)
    ├─ reputation_scores INSERT (스냅샷, INSERT-only)
    └─ 등급 변동 시 outbox_events INSERT (aggregateType="Company",
       eventType="CompanyReputationChanged")           ← 원자적(둘 다 커밋/롤백)
                     ↓ shared-common OutboxPublisherScheduler (FOR UPDATE SKIP LOCKED)
                 토픽: lemuel.company.reputation_changed
                       (KafkaOutboxPublisher 규칙 = lemuel.<aggregate>.<event_snake>)
                     ↓
[loan-service] CompanyReputationChangedConsumer (group=lemuel-loan)
    ├─ processed_events(consumer_group, event_id) PK 멱등 체크
    └─ company_reputation 프로젝션 UPSERT (stockCode PK) — 여신 심사 참고 지표
                     → GET /loans/company-reputation/{stockCode}
```

- **등급 변동 판정**: 직전 스냅샷 등급과 다를 때만 발행(최초 스냅샷=직전 없음도 변동으로 간주 →
  loan 이 초기 등급을 최소 1회 학습). 같은 등급이 유지되는 날은 발행하지 않는다.
- **페이로드**: `stockCode, snapshotDate, score, grade, previousGrade, articleCount,
  negativeCount, calculatedAt` — company 의 ObjectMapper 가 JavaTimeModule 미등록이라
  java.time 값은 문자열로 담는다.
- 3단 멱등 방어 동일 적용(outbox `event_id` UNIQUE → `processed_events` PK → 프로젝션 stockCode UPSERT).

**Phase 3 후속 (구현 완료)**:
- **셀러↔기업 매핑**: company 가 `lemuel.user.registered`(userId/email — 기업 연결 키 없음)를 소비해
  셀러 목록(`company_sellers`)을 축적하고, 운영자가 `POST /admin/company/sellers/{sellerId}/link/{stockCode}`
  로 명시 링크(`company_seller_links`)한다. 자동 매핑은 불가능(이벤트에 사업자번호 등 연결 키 부재) →
  명시 링크가 유일하게 정확한 방법. 평판 등급 변동 시 링크된 sellerId 를 이벤트 payload 에 동봉.
- **신용 반영**: loan 이 동봉된 sellerId 로 셀러별 프로젝션(`seller_reputation`)을 적재하고,
  `CreditPolicy` 가 등급별 한도 haircut 을 적용한다(A·B=1.0, C=0.85, D=0.70, E=0.0 = 차단;
  `app.loan.reputation.haircut.*` 설정). 등급 미상은 1.0(fail-open). 신청·실행 양 시점 모두 재검증.

**여전히 제외(후속)**: 금리 가산 반영(현재는 한도 haircut 만), user.registered 자동 매칭(연결 키 부재로 불가).

## 단계별 로드맵

| Phase | 범위 |
|---|---|
| **1 (이번)** | 서비스 골격 — company 마스터(시드) + 네이버 뉴스 수집 + 조회 REST + compose |
| **2** | ES 기사 색인·전문검색, 룰 기반 감성분석, reputation INSERT-only 스냅샷 |
| **3 (구현 완료)** | shared-common(common.outbox) 의존 추가, outbox 이벤트(`lemuel.company.reputation_changed`) 발행 → loan `CompanyReputationChangedConsumer` + `company_reputation` 프로젝션. CreditPolicy 반영·셀러 매핑은 후속 |
| **4 (진행 중)** | LLM 감성분석 교체(구현 완료 — `LlmSentimentAnalyzer`, `app.company.sentiment.provider=llm`, Claude opus-4-8 기본·키워드 폴백) / financial 마스터 단일화(ADR 0025 로 제안) / 관심기업 구독·알림(후속) |

## 결과

- (+) 비정형 평판 데이터가 여신·정산 리스크 신호로 연결되는 도메인 스토리 확보.
- (+) 코드·DB·이벤트 의존 0 인 독립 서비스 — 기존 4개 서비스에 영향 없음.
- (−) 기업 마스터가 financial 과 이중 관리된다 — 종목코드 표준화로 완화, 단일화는 후속 ADR.
- (−) 외부 API(네이버) 의존 — 키 미설정 시 수집만 비활성이고 조회는 시드/기존 데이터로 동작.
