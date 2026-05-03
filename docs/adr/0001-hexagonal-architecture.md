# ADR 0001 — Hexagonal Architecture (Ports & Adapters)

**Status:** Accepted
**Date:** 2026-04-23 (retrofitted — 최초 도입은 프로젝트 초기)

## Context

정산 도메인은 다음 외부 시스템과 엮여 있다:

- PG (Toss Payments) — 결제 승인/취소
- PostgreSQL — 원장 데이터
- Kafka / Redpanda — 이벤트 파이프라인
- Elasticsearch — 정산 검색/집계
- iText — 정산서 PDF
- Prometheus / Alertmanager — 관측성

이 중 어느 하나를 교체·업그레이드할 때마다 비즈니스 규칙(3% 수수료, 상태 전이, 환불 역정산)이 영향을 받으면 안 된다. 동시에 비즈니스 규칙은 **프레임워크·DB 와 무관하게** 단위 테스트가 가능해야 한다.

## Decision

패키지 구조를 **도메인 외곽-내향(inward) 의존**으로 강제한다:

```
{domain}/
├── domain/                 # 순수 POJO. Spring, JPA, Kafka 등 무의존.
├── application/
│   ├── port/in/            # 유스케이스 인터페이스 (인바운드 포트)
│   ├── port/out/           # 영속성·외부호출 인터페이스 (아웃바운드 포트)
│   └── service/            # 유스케이스 구현 — 도메인·포트만 사용
└── adapter/
    ├── in/                 # REST/Batch/Kafka 컨슈머 → 인바운드 포트 호출
    └── out/                # JPA/Kafka 프로듀서/Elasticsearch → 아웃바운드 포트 구현
```

**규칙:**

1. `domain` 은 `application`/`adapter` 를 import 하지 않는다.
2. `application` 은 `domain` 과 자기 포트만 참조한다. 다른 도메인의 `application`/`adapter` 를 직접 쓰지 않는다.
3. `adapter` 는 자기 도메인의 포트만 구현한다. 다른 도메인 포트를 implement 하지 않는다.
4. 교차 도메인 조회가 필요하면 **자기 도메인에 신규 아웃바운드 포트**를 두고 어댑터가 다른 도메인의 테이블을 읽는다 (예: `DailyTotalsJdbcAdapter`, `PeriodReconciliationJdbcAdapter`).

규칙 위반은 ArchUnit 테스트(`HexagonalArchitectureTest`)로 CI 에서 강제.

## Consequences

**Positive**
- 도메인 단위 테스트가 Spring 없이 가능 (POJO + Mock 포트). 현재 `Settlement`, `CashflowReconciliation` 등 수십 개 유닛 테스트가 프레임워크 없이 실행.
- 어댑터 교체 용이: `OutboxBackedEventPublisher` → `KafkaOutboxPublisher` 전환 시 도메인 코드 무수정.
- 새 도메인 추가 시 "어느 포트가 필요하고 어느 도메인 테이블을 읽을지" 가 명시적으로 드러남 (T3-⑨ `report` 도메인이 settlement/payment 테이블을 직접 읽는 선택이 그 예).

**Negative / Trade-offs**
- 포트 인터페이스로 인한 간접 호출 1단계 — 코드량 다소 증가.
- 교차 도메인 조회 시 포트 이름이 길어지는 경향 (`LoadPeriodReconciliationPort`, `LoadCapturedPaymentsPort` 등).

## Related

- ADR 0002 — Settlement 상태 머신
- ArchUnit 규칙: `src/test/java/github/lms/lemuel/architecture/HexagonalArchitectureTest.java`
