# ADR 0025 — 기업 마스터 단일화 (company ↔ financial) — 제안

- 상태: Proposed (검토용 — 아직 실행하지 않음)
- 일자: 2026-07-07
- 관련: ADR 0023(company-service), ADR 0020(이벤트 드리븐 프로젝션)

## 컨텍스트

기업(Company) 마스터가 **두 서비스에 이중으로 존재**한다:

- `financial-statements-service` — `companies`(종목코드 PK, corp_code, name, market) + DART 동기화 소유
- `company-service` — `companies`(동일 스키마) + 시드/조회 소유 (ADR 0023)

두 서비스는 종목코드(stockCode 6자리)를 공용 비즈니스 키로 쓰기로 표준화했지만(ADR 0023), 마스터
레코드 자체는 각자 적재·관리한다. 현재는 시드가 동일 20개사라 문제가 드러나지 않지만:

- 코스피 전체(~800)로 확장하면 두 서비스가 **각자 DART 를 동기화**해 corp_code·기업명 정합성이 어긋날 수 있다.
- 상장폐지·기업명 변경·인수합병 시 두 곳을 따로 갱신해야 한다(누락 위험).
- "기업 마스터의 단일 진실(single source of truth)"이 없다.

## 결정 (제안)

**company-service 를 기업 마스터의 오너로 승격**하고, financial 은 이벤트 드리븐 프로젝션으로 소비한다 —
ADR 0020(order→settlement)에서 확립한 패턴을 그대로 재사용한다. cross-DB·코드 의존 0 을 유지한다.

```
[company-service]  (기업 마스터 오너)
  ├─ companies (종목코드 PK, corp_code, name, market) — DART corpCode 동기화 소유
  └─ 변경 시 outbox_events INSERT → lemuel.company.synced
                     ↓ shared-common 폴러
[financial-statements-service]  (프로젝션 소비)
  └─ CompanySyncedConsumer → 자체 companies 프로젝션 upsert (processed_events 멱등)
     ※ financial 은 더 이상 DART 기업 동기화를 직접 하지 않고, 재무제표(financial_statements)만 소유
```

### 왜 company 가 오너인가

- 뉴스·평판·셀러링크 등 "기업 엔티티" 중심 기능이 company 에 모여 있다.
- financial 의 관심사는 **재무제표 수치**이지 기업 마스터의 라이프사이클이 아니다.
- company 는 이미 shared-common outbox 를 물었다(Phase 3) — 발행 인프라 재사용.

### 마이그레이션 (Strangler, 무중단)

1. company 에 `lemuel.company.synced` 발행 추가(마스터 변경 시). financial 에 컨슈머 + 프로젝션 컷오버 스위치.
2. 백필: company 가 전체 마스터를 재발행하거나 financial 이 초기 1회 REST 백필.
3. financial 의 DART **기업** 동기화 비활성(재무제표 동기화는 유지). 기업명/corp_code 는 프로젝션에서 읽음.
4. 관측: 프로젝션 lag 게이지, 정합성 대사(company 합계 vs financial 프로젝션 합계) — ADR 0020 recon 패턴.

## 대안

- **A. 공유 라이브러리로 마스터 배포**: shared-common 에 기업 마스터를 두는 방식 — 그러나 마스터는
  런타임 데이터이지 코드가 아니므로 부적합(shared-common 은 무상태 인프라).
- **B. 현행 유지(이중 관리) + 종목코드 표준화만**: 지금 상태. 소규모/시드 단계에선 충분하나 확장 시 정합성 부채.
- **C. financial 을 오너로**: 재무제표 서비스가 기업 라이프사이클까지 소유 — 관심사 불일치, 비추천.

## 결과

- (+) 기업 마스터 단일 진실 확립, DART 기업 동기화 1곳으로 수렴, 정합성 부채 해소.
- (+) 기존 ADR 0020 이벤트 프로젝션 패턴 재사용 — 새 아키텍처 학습 비용 0.
- (−) financial 에 컨슈머·프로젝션·백필 도입 = 실질 리팩터(그래서 이 ADR 은 제안 단계, 실행은 별도 작업).
- (−) 초기 컷오버 중 이중 소유 구간 관리 필요(Strangler 표준 트레이드오프).

## 다음 단계

이 제안을 Accepted 로 올린 뒤 별도 구현 작업으로: (1) company `lemuel.company.synced` 발행,
(2) financial 컨슈머+프로젝션, (3) DART 기업 동기화 컷오버, (4) 정합성 대사. 코스피 전체 확장 직전에
착수하는 것을 권장한다(시드 단계에서는 B 로 충분).
