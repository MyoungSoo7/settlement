# DONE_CRITERIA — Lemuel 완료 기준 (Definition of Done)

> [`PLAN.md`](./PLAN.md) 의 각 Phase·기능이 **"완료"라고 부를 수 있는 객관 조건**. 원칙: **완료는 주장이 아니라 게이트 출력이다**
> (LLM 판단 아님 — `verify-before-done` 스킬). 각 기준은 사람 판단이 아니라 **명령·테스트·가드로 재현 가능**해야 한다.

- 기준 시점: 2026-07-24
- 완료 판정 정본: `./gradlew :<module>:test` + `:<module>:jacocoTestCoverageVerification` 통과. 회색지대는 게이트가 정답.

> ⚠️ **이 문서의 체크박스는 미검증 템플릿이다.** 모든 `[ ]` 는 *아직 게이트를 돌려 확인하지 않았다*는 뜻이며,
> 본문의 ✅/🟡 상태 표기는 [`STATUS.md`](./STATUS.md)·[`SPEC.md`](./SPEC.md) 의 서술을 옮긴 것이지 이 문서 작성 과정에서
> 실행·검증한 결과가 아니다. 실제 완료 증명은 각 항목의 판정 명령을 직접 돌려 `[x]` 로 채우는 것으로만 성립한다
> (이 문서 자신의 원칙: "완료는 주장이 아니라 게이트 출력이다").

---

## 0. 전역 게이트 (모든 기능에 무조건 적용)
> 하나라도 빨간불이면 그 기능은 DONE 이 아니다.

| # | 게이트 | 판정 명령 / 기계 강제 |
|---|--------|----------------------|
| G1 | 빌드 통과 | `./gradlew build` (컴파일·전 모듈) |
| G2 | 단위+통합 테스트 그린 | `./gradlew :<module>:test` — **skip=0 확인**(Docker 죽으면 Testcontainers 조용히 skip) |
| G3 | 커버리지 게이트 | `:<module>:jacocoTestCoverageVerification` — **LINE ≥ 90%**, 핵심 도메인 INSTRUCTION ≥ 80% |
| G4 | 헥사고날 방향 | ArchUnit — 도메인이 어댑터 import 0, 포트 우회 0 |
| G5 | OO 구조 게이트 | `guard.mjs` OO-* 규칙(실시간) + `oo-gate.test.mjs`(CI 전수) — 도메인 public setter/@Data 0, 금융 5서비스 generic IAE 0 |
| G6 | MSA 경계 | settlement 에 `implementation(project(":order-service"))` 0, order import·cross-DB 조인 0 (guard 강제) |
| G7 | 금액 안전 | 금액 필드 `double`/`float` 0 — BigDecimal 강제(guard MONEY-PRIMITIVE) |
| G8 | 이벤트 계약 | 프로듀서·컨슈머 양방향 계약 테스트 그린(14토픽, testFixtures 단일 출처, ADR 0024) |
| G9 | 하네스 무결성 | `node scripts/harness/harness-audit.mjs` — 문서 드리프트·라우팅 dangling·가드 무결성 0 |
| G10 | 절차 규율 | 착수 전 `tdd-discipline`, 조사 시 `debugging-discipline`, 완료 직전 `verify-before-done` 로드 |

---

## 1. 횡단 관심사 완료 기준 (Cross-cutting)
| 관심사 | 완료 조건(객관) |
|--------|----------------|
| 인증 | `JWT_SECRET` 미설정 시 기동 실패(≥32바이트), 발급 토큰에 subject·role·uid 클레임, 만료·서명 검증 테스트 |
| 인가(IDOR) | 셀러 리소스 식별자를 요청에서 신뢰하지 않고 JWT 주체 파생, 소유권 불일치 시 **403** 테스트 존재 |
| Outbox 멱등 | 같은 event_id 2회 발행 → 컨슈머 1회 처리(멱등 IT), 도메인 UNIQUE 충돌 시 무해 |
| 상태머신 | 비정상 전이 시도 → 도메인 예외(타입 예외, generic IAE 아님), 전이표(SPEC §4) 전 케이스 테스트 |
| 원장 | 반쪽 전표 삽입 불가(차1·대1 팩토리), POSTED 수정 불가·역분개만, 시산표 차대 균형 = 0 |
| 보안 fail-closed | `app.security.internal-key-required=true` 에서 키 미설정 요청 거부 테스트 |

---

## 2. 서비스별 완료 기준 (핵심만 — 상세 규칙은 `*-rules` 스킬)

### order-service ✅
- [ ] 주문 재고 조건부 UPDATE — 동시 주문에서 오버셀 0 (동시성 IT)
- [ ] 환불 동시성 — Pessimistic Lock + Idempotency-Key, 초과환불 차단 IT
- [ ] `payment.captured/refunded · order.created · user.registered · product.changed` Outbox 발행 + 계약 테스트
- [ ] `/internal/recon` 자기 합계만 노출(X-Internal-Api-Key), cross-DB 조회 0

### settlement-service ✅ 🟡
- [ ] REST 는 조회 전용 — 생성/확정 경로가 REST 에 노출되지 않음
- [ ] `payment.captured` → 정산 생성, `SettlementConfirmJob` → 확정(Batch), 각 멱등
- [ ] 수수료 등급 스냅샷 영구보존(정산시점 `commission_rate` 불변), 홀드백 등급별 정확
- [ ] 확정·홀드백 해제 → Payout 멱등 자동 생성(2회 실행 2회차 0건 IT)
- [ ] 차지백·PG 대사 승인 → 역분개 1:1 연동, 시산표 균형 유지
- [ ] 🟡 **잔여**: payout 실송금 트리거 + 계좌 레지스트리(§잔여 8-2)

### loan-service ✅
- [ ] 기업대출 신청 시점 E등급·한도초과 **422**, 실행은 ADMIN + 비관적 락(이중지급 0)
- [ ] 상환 시뮬레이션 순수(영속화·부수효과 0), 원 단위 반올림·마지막 회차 잔여 흡수로 원금 합 일치
- [ ] 원장 2전표 균형 + `loan.corporate_loan_disbursed` 계약 테스트

### investment-service ✅
- [ ] 투자점수 3축 합 0~100, 등급·적격(≥60) 경계값 테스트
- [ ] 재원 부족/부적격 시 **422**, sellerId JWT 파생·소유권 대조 403
- [ ] 집행 시 `investment.executed` 발행 + 상태머신 강제

### account-service 🟡
- [ ] 6토픽 소비 → 전표당 차1·대1, 멱등 2단(processed_events + (source_topic,ref_type,ref_id) UNIQUE)
- [ ] **발행 코드 0**(소비 전용 — guard 강제)
- [ ] 🟡 **잔여**: payout 현금 유출 GL 인식 + 시산표 실검증(ADR 0026 결정 대기, §잔여 8-1)

### 공개조회 위성 (financial·economics·company·market·common-data) ✅ 🟡
- [ ] GET 공개, `/admin/**` 는 X-Internal-Api-Key 게이트(운영 fail-closed)
- [ ] financial: 5개 비율 도메인 계산·미저장(null=N/A), PER/PBR 미계산 경계
- [ ] market: 금액 BigDecimal·수량 BigInteger, 피드값 보존, **PER/PBR 절대 미계산**
- [ ] company: 기사 본문 미저장(url_hash 멱등), 문서함 ADMIN/MANAGER JWT, 평판 INSERT-only 스냅샷
- [ ] common-data: SSRF 가드(내부/사설/메타데이터 IP 거절), (source,record_key) 멱등 upsert — 🟡 실수집 미검증(§잔여 8-4)
- [ ] 타 서비스와 코드·DB·이벤트 의존 0 (ArchUnit/guard)

### operation-service ✅
- [ ] webhook 항상 200, `(source,correlation_key)` 활성 유일성, refire 병합
- [ ] opssignal 절대 throw 금지·Outbox 미사용(fire-and-forget), failure_rate 산출
- [ ] Phase 3 이상탐지: 5분 롤링 z-score → ANOMALY 인시던트 자동 생성/자동해제

### ai-service ✅
- [ ] PII 마스킹 단일 초크포인트(저장·전송 전), provider 정확히 하나 등록, out/llm 격리(ArchUnit)
- [ ] JWT USER 이상 + 비용가드(분5/일100), LLM 실패 시 폴백 없음(503+무저장)

### organization-service ✅ 🟡
- [ ] JWT 인증 필수, 인가는 조직 내 역할(OrgAuthorizer)로 판정
- [ ] 마지막 OWNER 제거 불가 불변식 테스트, 상태머신 강제
- [ ] `organization.created · member_joined` Outbox 발행 + 계약 스키마 존재 — 🟡 소비처 미배선(의도된 상태)

### 폴리글랏 7종 ✅
- [ ] 공통: `GET /health`(또는 `/healthz`) → `{"status":"UP"}`, 무-외부의존 단독 기동, 테스트 그린
- [ ] `polyglot-ci.yml` 변경 서비스만 동적 매트릭스로 테스트·이미지 푸시
- [ ] notification: 채널별 타임아웃(3s)/재시도(3회) 격리, eventId 멱등(TTL 30분)
- [ ] payment-webhook: HMAC 서명검증·멱등 → `payment.confirmed` 발행

### gateway-service ✅
- [ ] 경로 predicate 라우팅만(자체 인증 필터 0), 위성 `/admin/**` 외부 미노출
- [ ] 신규 서비스 배선 5곳(스캔·JPA·gateway·nginx·Dockerfile) 완료 — 404/크래시 0

---

## 3. 잔여 작업 완료 기준 (Open — PLAN §8 대응)
| # | 작업 | 완료로 인정하는 조건 |
|---|------|---------------------|
| 8-1 | account payout 현금흐름 인식 | ADR 0026 결정 반영 커밋 + `payout.completed` → DR SELLER_PAYABLE/CR CASH 전표 IT + **시산표 차대 0 실검증** |
| 8-2 | payout 실송금 + 계좌 레지스트리 | 계좌 등록/검증 도메인 + 실송금 트리거(mock→실연동 경계) + 송금 상태머신(SENDING 취소 불허) IT |
| 8-3 | ADR 0022 스키마 레지스트리 검토 | 도입/보류 결론 ADR 기록(0024 대비 이점·비용 명시) |
| 8-4 | common-data 실수집 | `DATA_GO_KR_API_KEY` 로 소스 등록→수집→조회 E2E 1건 실증 |
| 8-5 | operation Phase 4 AI 브리핑 | 이상탐지 인시던트 → AI 요약 브리핑 생성 경로 + 테스트 |
| 8-6 | organization 소비처 배선 | 소비 유스케이스 확정 → ADR 0024 절차로 계약 편입 + 양방향 계약 테스트 |
| 8-7 | 신규 서비스 통합테스트 보강 | 대상 모듈 `jacocoTestCoverageVerification` LINE ≥ 90% 통과 |

---

## 4. 신규 기능 착수 시 DoD 체크리스트 (템플릿)
> 아무 기능이나 "완료" 선언 전 이 순서로 통과 증거를 남긴다.

1. [ ] `tdd-discipline` — 실패하는 테스트를 먼저 목격
2. [ ] 도메인 규칙 스킬(`*-rules`) 로드 후 불변식 준수
3. [ ] 구현 — 상태머신·BigDecimal·원장 균형·IDOR·멱등 준수
4. [ ] `./gradlew :<module>:test` 그린(**skip=0**) + `:<module>:jacocoTestCoverageVerification` LINE ≥ 90%
5. [ ] cross-service 이벤트 건드렸으면 양방향 계약 테스트 그린
6. [ ] `guard.mjs` / ArchUnit / `harness-audit` 위반 0
7. [ ] `verify-before-done` — 게이트 출력으로 완료 증명(주장 아님)
8. [ ] STATUS.md 갱신(진척·다음 할 일)
