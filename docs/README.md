# Lemuel 문서 인덱스

이커머스 + 정산 MSA 플랫폼(**Java 25 / Spring Boot 4 / PostgreSQL 17 / Kafka / Elasticsearch**)의 문서 모음.
프로젝트 개요는 루트 [`README.md`](../README.md), 코딩/아키텍처 규칙은 [`CLAUDE.md`](../CLAUDE.md) 참조.

---

## 🧭 빠른 시작

| 목적 | 문서 |
|------|------|
| 프로젝트가 뭐 하는지 | [../README.md](../README.md) |
| 로컬에서 띄우기 | [etc/SETUP.md](etc/SETUP.md), [etc/DEMO.md](etc/DEMO.md), [demo/README.md](demo/README.md) |
| 배포/인프라 | [etc/DEPLOYMENT.md](etc/DEPLOYMENT.md), [etc/INFRASTRUCTURE.md](etc/INFRASTRUCTURE.md), [etc/kube.md](etc/kube.md) |
| 장애 대응 | [runbook/](runbook/README.md) |

---

## 📚 핵심 분석 문서 (도메인·CS 관점)

프로젝트 코드를 근거로 작성한 주제별 심층 분석.

| 문서 | 내용 |
|------|------|
| [정산.md](정산.md) | **핵심 도메인** — 정산 계산·상태머신·생성/확정/역정산 흐름, 홀드백·수수료·정산주기, 정합성 장치 |
| [DB.md](DB.md) | 도메인 ERD (1부) + RDBMS 비교 분석 PostgreSQL vs MySQL vs MariaDB (2부) |
| [자료구조.md](자료구조.md) | 프로젝트에서 쓰이는 자료구조 + 백엔드 필수 자료구조 부록 |
| [알고리즘.md](알고리즘.md) | 정산금 계산·PG 대사 매칭·한글 Slug·Outbox claim 등 구현 알고리즘 |
| [디자인패턴.md](디자인패턴.md) | 디자인 패턴 개념 + 프로젝트 적용 사례(헥사고날·Outbox·Strategy·State 등) |
| [memory.md](memory.md) | 메모리 사용 분석 — JVM 힙·캐시·커넥션 풀·결과 메모리 경계 |
| [network.md](network.md) | 네트워크 통신 구조 — 게이트웨이 라우팅·Kafka 비동기·PG 연동·회복탄력성 |
| [프로메테우스.md](프로메테우스.md) | Prometheus 소개·사용법 + 프로젝트 적용(커스텀 메트릭·알림 규칙·HealthIndicator) |
| [카프카.md](카프카.md) | Kafka 소개 + 프로젝트 적용(Outbox 경유 발행·멱등 컨슈머·DLT/재처리·토픽 설계) |
| [자바.md](자바.md) | Java 본질·11/17/21/25 쟁점 + 프로젝트(Java 25) 성능 이점 + 부록 JVM·GC |
| [자기소개.md](자기소개.md) | 면접용 자기소개 — 길이별 스크립트·STAR 어필 포인트·예상 Q&A |

---

## 🏛 아키텍처 결정 기록 (ADR)

[adr/README.md](adr/README.md) · 왜 그렇게 설계했는지의 근거 기록.

| ADR | 주제 |
|-----|------|
| [0001](adr/0001-hexagonal-architecture.md) | 헥사고날 아키텍처 |
| [0002](adr/0002-settlement-state-machine.md) | 정산 상태 머신 |
| [0003](adr/0003-transactional-outbox-pattern.md) | Transactional Outbox |
| [0004](adr/0004-reverse-settlement-via-adjustment.md) | Adjustment 기반 역정산 |
| [0005](adr/0005-kafka-vs-application-events.md) | Kafka vs 애플리케이션 이벤트 |
| [0006](adr/0006-resilience4j-tosspg.md) | Resilience4j + Toss PG |
| [0007](adr/0007-daily-reconciliation-and-ledger-invariants.md) | 일일 대사 + 원장 불변식 |
| [0008](adr/0008-cashflow-report-domain.md) | 캐시플로우 리포트 도메인 |
| [0009](adr/0009-boot4-migration-module-split.md) | Boot4 마이그레이션 + 모듈 분리 |
| [0010](adr/0010-multi-pg-routing-and-bulkhead.md) | 다중 PG 라우팅 + 벌크헤드 |
| [0011](adr/0011-sku-variant-with-optimistic-lock.md) | SKU 변형 + 낙관적 락 |
| [0012](adr/0012-distributed-tracing-across-outbox.md) | Outbox 경계 분산 추적 |
| [0013](adr/0013-split-payment-with-tenders.md) | 복합 결제(tenders) |
| [0014](adr/0014-tier-based-settlement-cycle.md) | 등급별 정산 주기 |
| [0015](adr/0015-settlement-holdback-policy.md) | 정산 홀드백 정책 |
| [0016](adr/0016-payout-domain-firm-banking.md) | 지급 도메인 + 펌뱅킹 |
| [0017](adr/0017-kafka-consumer-dlt-and-replay.md) | Kafka DLT + 재처리 |
| [0018](adr/0018-chargeback-domain.md) | 차지백 도메인 |

---

## 📊 다이어그램

| 문서 | 내용 |
|------|------|
| [diagrams/architecture.md](diagrams/architecture.md) | 시스템 아키텍처 |
| [diagrams/ERD.md](diagrams/ERD.md) | ERD (다이어그램 버전) |
| [diagrams/sequence-payment-to-settlement.md](diagrams/sequence-payment-to-settlement.md) | 결제→정산 시퀀스 |
| [diagrams/sequence-multi-item-checkout.md](diagrams/sequence-multi-item-checkout.md) | 다중 상품 주문 시퀀스 |
| [diagrams/sequence-pg-reconciliation.md](diagrams/sequence-pg-reconciliation.md) | PG 대사 시퀀스 |

---

## 🚨 운영 런북 (Runbook)

[runbook/README.md](runbook/README.md) · 장애·운영 상황별 대응 절차.

| 런북 | 상황 |
|------|------|
| [settlement-mismatch.md](runbook/settlement-mismatch.md) | 정산 불일치 |
| [cashflow-reconciliation.md](runbook/cashflow-reconciliation.md) | 캐시플로우 대사 |
| [outbox-backlog.md](runbook/outbox-backlog.md) | Outbox 적체 |
| [toss-pg-outage.md](runbook/toss-pg-outage.md) | Toss PG 장애 |
| [db-migration-rollback.md](runbook/db-migration-rollback.md) | DB 마이그레이션 롤백 |
| [disaster-recovery.md](runbook/disaster-recovery.md) | 재해 복구 |

---

## 🧩 디자인 패턴 노트

| 문서 | 내용 |
|------|------|
| [design-pattern/teplate-pattern.md](design-pattern/teplate-pattern.md) | 템플릿 메서드 패턴 |
| [design-pattern/callback.md](design-pattern/callback.md) | 콜백 패턴 |
| [디자인패턴.md](디자인패턴.md) | 패턴 개념 + 프로젝트 적용 종합 |

---

## 📁 기타 문서 (etc/)

운영·설계·보안·테스트·포트폴리오 등 기타 참고 문서.

| 분류 | 문서 |
|------|------|
| 아키텍처/설계 | [etc/ARCHITECTURE.md](etc/ARCHITECTURE.md), [etc/api-architect.md](etc/api-architect.md), [etc/ledger-domain-design.md](etc/ledger-domain-design.md) |
| 보안 | [etc/SECURITY.md](etc/SECURITY.md) |
| 모니터링/관측 | [etc/MONITORING.md](etc/MONITORING.md) |
| CI/CD | [etc/CI_CONFIGURATION.md](etc/CI_CONFIGURATION.md), [etc/CI_SETUP_GUIDE.md](etc/CI_SETUP_GUIDE.md) |
| 배포/인프라 | [etc/DEPLOYMENT.md](etc/DEPLOYMENT.md), [etc/INFRASTRUCTURE.md](etc/INFRASTRUCTURE.md), [etc/kube.md](etc/kube.md) |
| 기능/명세 | [etc/functional-spec.md](etc/functional-spec.md), [etc/process-definition.md](etc/process-definition.md), [etc/screen-design.md](etc/screen-design.md) |
| 시퀀스 | [etc/sequence-diagram.md](etc/sequence-diagram.md), [etc/SEQUENCE_DIAGRAMS.md](etc/SEQUENCE_DIAGRAMS.md), [etc/SEQUENCE-ORDER-VS-REFUND.md](etc/SEQUENCE-ORDER-VS-REFUND.md) |
| 테스트 | [etc/test-report.md](etc/test-report.md), [etc/troubleshooting.md](etc/troubleshooting.md) |
| 학습/면접 | [etc/INTERVIEW_QA.md](etc/INTERVIEW_QA.md), [etc/CODING_TEST_PATTERNS.md](etc/CODING_TEST_PATTERNS.md), [etc/LEGACY-CASE-STUDY.md](etc/LEGACY-CASE-STUDY.md) |
| 평가/포트폴리오 | [etc/PROJECT-EVALUATION.md](etc/PROJECT-EVALUATION.md), [etc/PORTFOLIO.md](etc/PORTFOLIO.md) |
| 진행/메모 | [etc/IMPLEMENTATION_GUIDE.md](etc/IMPLEMENTATION_GUIDE.md), [etc/IMPLEMENTATION_COMPLETED.md](etc/IMPLEMENTATION_COMPLETED.md), [etc/next.md](etc/next.md), [etc/manual.md](etc/manual.md) |

---

## 🗂 데모 / 시드 데이터

| 파일 | 내용 |
|------|------|
| [demo/README.md](demo/README.md) | 데모 가이드 |
| [demo/postman-collection.json](demo/postman-collection.json) | Postman 컬렉션 |
| [demo/seed-data.sql](demo/seed-data.sql), [demo/seed.sh](demo/seed.sh) | 시드 데이터 |
| [demo/sample-pg-reconciliation.csv](demo/sample-pg-reconciliation.csv) | PG 대사 샘플 CSV |

---

## 디렉터리 구조

```
docs/
├── README.md            ← 이 문서 (인덱스)
├── 정산.md / DB.md / 자료구조.md / 알고리즘.md / 디자인패턴.md / memory.md / network.md
├── adr/                 아키텍처 결정 기록 (0001~0018)
├── diagrams/            아키텍처·ERD·시퀀스 다이어그램
├── runbook/             운영 장애 대응 런북
├── design-pattern/      디자인 패턴 노트
├── demo/                데모·시드 데이터
└── etc/                 기타 설계·운영·학습 문서
```
