# Seed — 정산 코어 구조 안전성 & 엣지 케이스 감사

> Ouroboros interview(Path B, 직접 진행)로 도출. 실행 엔진 Windows 불가로 MCP seed→run 우회.
> 원 세션: interview_20260724_015522 (MCP initialize timeout — 사용 불가)

## goal (restated, 한 줄)

`settlement`·`account`(GL)·`loan` 세 서비스의 아키텍처 경계·금융 불변식·이벤트/멱등 불변식이 실제로 강제되는지를
실행 증거(게이트·테스트)와 코드 논증으로 검증하고, **뚫려 있는 갭을 심각도(HIGH/MED/LOW)·재현 시나리오·근거(file:line)와 함께**
정리한 감사 리포트를 산출한다. (코드 수정은 비목표)

## constraints (user-stated)

- **범위**: settlement + account + loan 3서비스만. (커머스/위성/폴리글랏 제외)
- **초점 축(전수)**: ① 아키텍처 경계(헥사고날 의존 방향, MSA import 0, 포트 우회) ② 금융 불변식(BigDecimal,
  복식부기 차대 균형·POSTED 불변·역분개, 상태머신 전이, 수수료/홀드백 보존) ③ 이벤트/멱등(Outbox 원자성,
  3단 멱등, 계약 드리프트 ADR 0024, 프로젝션 뷰 정합)
- **방법**: 실행 + 정적 결합. 게이트/테스트(ArchUnit·guard.mjs·gradle test·jacoco·통합테스트)를 실제로 돌려
  출력을 증거로 삼고, 게이트가 못 잡는 사각은 코드 논증으로 보강.
- **엣지 성격**: 도메인 시나리오(환불 동시성·역정산·홀드백 경계·부분 캡처·상환 saga·투자 재원) +
  동시성·실패 모드(락 경합·부분 실패·이벤트 순서·중복/유실·스케줄러 락·Outbox 적체). 전부 열거 후 커버리지 표.
- **합격 기준**: 갭 중심 + 심각도. 각 갭은 (심각도, 재현 시나리오, 근거 file:line) 필수. 안전 확인된 곳은 요약.
- **깊이 상한(자리비움 기본값)**: 핵심 불변식 집중 — 실행 게이트 전부 + 대표 엣지 시나리오, 헤드라인 갭 우선.

## out of scope (user-stated / inferred)

- 코드 수정·픽스·테스트 추가 (리포트만). 발견은 후속 과제로 남김.
- 커머스(order)·위성 서비스·게이트웨이·폴리글랏 7종.
- 우로보로스 정식 seed→run 파이프라인(Windows 불가). WSL 이관은 별도.

## acceptance_criteria

1. 3서비스 각각에 대해 ①②③ 축의 불변식 목록을 열거한 **커버리지 표**(강제 수단: guard/ArchUnit/테스트/미강제) 존재.
2. 실행 증거: ArchUnit·guard.mjs(harness-audit)·해당 모듈 test·jacoco 검증 태스크의 **실제 출력**이 첨부/요약됨
   (Docker 있으면 Testcontainers 통합테스트 실행, skip=0 확인 — 가짜 GREEN 금지).
3. 발견된 **갭 목록**이 심각도(HIGH/MED/LOW) 정렬 + 재현 시나리오 + 근거 file:line 과 함께 제시됨.
4. 대표 도메인 엣지 시나리오(환불 동시성/역정산/홀드백/부분 캡처/상환 saga/멱등 재생)가 커버 여부와 함께 판정됨.
5. "안전이 실증된 곳"과 "미강제/미검증인 곳"이 한눈에 구분됨.

## 환경 사실 (수집)

- Docker 29.6.2 가용 → Testcontainers 통합테스트 실행 가능(skip 없이).
- 하네스 스크립트: scripts/harness/{guard,harness-audit,...}.mjs 존재.
- 대상 모듈 디렉토리: settlement-service, account-service, loan-service 확인.

## 자리비움 기본값(돌아오면 정정 가능)

- 실행 주체 = 지금 세션에서 내가 직접 수행 (Ouroboros 우회)
- 깊이 = 핵심 불변식 집중(1패스, 헤드라인 갭 우선)
