# Seed — card-service 재원 이중 사용 해소 (deposit hold/offset 연계 상계)

> **상태: DESIGN** (미구현) · 정본 데이터: [`card-service-funding-offset.seed.yaml`](card-service-funding-offset.seed.yaml)
> ⚠️ 자매 Seed 들과 성격이 다르다 — 이것은 **as-is 회귀 기준선이 아니라 실행 대상 스펙**이다.
> card-service 의 as-is 기준선은 [`card-service-corporate-card`](card-service-corporate-card.seed.md) 다 —
> 이 스펙이 닫으려는 구멍(`R = 0.70` 이 위험을 줄일 뿐 닫지 않는다)은 그 Seed 의 KI-1 로 기록돼 있다.
> 산출 경위: `card-service.md` 역산 PRD §12-A(최상위 리스크) → 추적항목 C-1 에서 파생.
> Ouroboros MCP(interview→generate_seed)를 쓸 수 없어(무크레딧 키 캐시) PRD 근거 위에서 손으로 작성했다.

## Goal (한 줄)

**card-service 의 마스터 한도가 담보로 삼는 미지급 정산금을 deposit-service 의 hold 로 선점하고,
명세서 청구 시 offset 으로 상계해, 카드 이용과 정산 지급이 같은 재원을 두 번 쓰는 구조를 닫는다.**

## 관련 모듈

| 모듈                  | 역할      | 왜                                                              |
| --------------------- | --------- | --------------------------------------------------------------- |
| `card-service`        | primary   | 한도 산정·재산정 배치·승인·명세서 청구 — 선점/상계 호출이 붙을 자리 |
| `deposit-service`     | primary   | 상계 상대편 원장(hold·entry·shortfall + `/admin/deposits` 콘솔) |
| `account-service`     | reference | 재원 `F = sellerPayable + holdbackPayable` 의 **유일한 정답지**(ADR 0030) |
| `settlement-service`  | reference | 카드가 선점한 재원을 지급이 침범하지 못하게 하는 반대편 통제점  |

## 제약 (요지 — 전체는 yaml)

1. **MSA 경계** — card 는 deposit 을 코드·DB 로 의존하지 않는다. 내부 API 또는 Kafka 만, cross-DB 조인 0.
2. **재원의 정답지는 GL** — deposit hold 는 "선점 표시"이지 재원의 사본이 아니다. card 가 재원을 자체 보관하지 않는다.
3. **`DeclineReason` 4종 고정** — 선점·상계 실패를 새 거절 사유로 만들지 않는다(ADR 0022 파괴적 변경).
4. **폴백 금지** — 재원·선점 조회 실패는 503 으로 끝낸다. 추정으로 여신을 내주지 않는다.
5. **승인 p99 300ms 예산** — 지키지 못하면 선점을 한도 산정·재산정 시점으로 미룬다.
6. **락 순서 유지** — 계정 행 잠금 → 서브한도 합계 재계산 → 검증 → 저장·발행(동일 트랜잭션).
7. **Outbox 경유·파티션 키 `cardAccountId`**, 금액은 `BigDecimal`/`toPlainString()`(N5).
8. **멱등** — 재전송·배치 재실행이 이중 집행을 만들지 않는다.
9. **기존 한도를 이 변경만으로 축소하지 않는다** — 축소가 필요하면 재산정 배치의 클램프·통지 경로를 탄다.

## 수용 기준 (요약 8항)

| # | 기준 |
|---|------|
| 1 | 한도가 확정되는 **모든 경로**에서 같은 금액의 hold 가 존재하고, 한쪽만 성공한 상태로 끝나지 않는다 |
| 2 | 지급 가능액 산정에서 활성 카드 hold 가 차감되고, 그 사실이 셀러 조회 가능한 형태로 남는다 |
| 3 | 명세서 CLOSED 시 총액만큼 offset 실행, 부족분은 `DepositOffsetShortfall` 로 남아 후속 회수 대상 |
| 4 | 같은 `statementId` 재요청에도 offset 은 1회. 선점 갱신 재실행은 하나의 값으로 수렴 |
| 5 | deposit 연계 실패는 폴백 없이 실패 — 503 / 배치는 해당 계정만 옛 한도 유지 / 거절은 기존 `LIMIT_EXCEEDED` 로만 |
| 6 | MSA 경계 유지 — 빌드 의존성 0, 테이블 직접 조회 0, ArchUnit 이 어댑터 외 외부 호출 차단 |
| 7 | 상계가 걸린 뒤 인정비율 R 을 올릴 수 있고, 재산정 1회로 수렴하며 `masterLimit >= Σ 활성 서브한도` 가 한 번도 깨지지 않는다 |
| 8 | 선점 총액·상계 성공·shortfall 건수/잔액이 메트릭으로 노출된다 |

## 평가 원칙

- 성패는 "R 을 얼마로 올렸는가"가 아니라 **"선점 없이 나가는 지급이 0인가"** 로 판정한다.
- 동시성은 단위 테스트로 판정하지 않는다 — 한도 산정·승인·지급이 동시에 도는 통합 시나리오로 본다.
- **부분 성공(한도만 바뀌고 hold 미생성)이 관측되면 실패다** — 회계 정합성에 "드물게"는 허용 기준이 아니다.

## Exit 조건

- `:card-service:test` + `jacocoTestCoverageVerification`(LINE 90%) 통과
- deposit 을 수정했다면 `:deposit-service:` 동일 게이트 통과
- `CardIssuanceLimitConcurrencyIT`·`ConcurrentAuthorizationIT` 가 선점 도입 후에도 통과
- 신규/변경 cross-service 토픽이 있으면 계약 스키마·정본 샘플·양방향 테스트 배선(ADR 0024)
- `guard.mjs --staged` + `harness-audit` 통과
- PRD §12-A 와 추적항목 C-1 을 해소로 갱신

## 구현 시점으로 미룬 결정 (5건)

1. hold 의 소유 단위 — 계정당 1건 갱신 vs 변경마다 신규 적재 후 만료 정리
2. settlement 반영 지점 — 확정 배치의 즉시지급액 산정 vs payout 직전 검사(차감 순서 T-4 와의 우선순위 포함)
3. deposit 에 `/internal/**` 신설 vs 기존 `/admin/deposits` 재사용 (Kafka 경로는 컨슈머 배선이 선행)
4. 부족분 회수 주체 — deposit `DepositOffsetShortfall` vs settlement `SellerRecovery` (이중 계상 금지)
5. R 의 목표값과 전환 절차(일괄 vs 단계적)

## 알려진 문서 참조 오류

yaml 의 `metadata.source` 와 `exit_conditions` 가 `docs/prd/card-service.md` 를 가리키지만, 실제 경로는
[`../prd/card-service.md`](../prd/card-service.md)(`docs/plan/prd/`)다. 문서 이동 이후 갱신되지 않은 dangling 참조다.
