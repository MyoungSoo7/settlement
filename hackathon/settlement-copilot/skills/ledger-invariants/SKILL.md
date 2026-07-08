---
name: ledger-invariants
description: 복식부기 원장 불변식 — 차/대 균형, POSTED 불변, 역분개 원칙. 원장 코드 작성·시산표 불일치 조사 시 로드.
---

# 원장 불변식 (ADR 0007)

## 불변식 (하나라도 깨지면 회계 사고)

1. **차/대 균형**: 하나의 분개 묶음(journal) 안에서 `Σ DEBIT == Σ CREDIT`. 단일 row 삽입 API 를
   만들지 마라 — 반드시 균형 잡힌 묶음 단위로 저장.
2. **POSTED 불변**: `PENDING → POSTED → REVERSED` 만 허용. POSTED 된 분개의 금액/계정 UPDATE 금지.
3. **삭제 금지**: DELETE 는 어떤 경우에도 없다. 취소는 **역분개(REVERSED) row 추가**.
4. **원천 추적**: 모든 분개는 원천(settlementId / refundId / payoutId)을 가진다. orphan 분개 금지.

## 정정 절차 (개발자가 "원장 숫자 틀렸어요" 라고 하면)

1. 틀린 분개를 UPDATE 하려는 시도를 막는다.
2. 원분개 전체를 뒤집는 역분개를 추가한다 (차↔대 반전, 동일 금액).
3. 올바른 분개를 새로 추가한다.
4. 조회 화면은 (원분개 + 역분개 + 정정분개) 를 모두 보여준다 — 감사 추적.

## 시산표 불일치 조사 순서

MCP `ledger_entries(from, to)` 가 `trialBalance.balanced=false` 를 반환하면:

1. **기간 경계**: 분개 일자 vs 정산 일자 기준 혼용 확인 (조회 기준일 필드가 무엇인지).
2. **반쪽 분개**: 예외로 인해 journal 묶음 일부만 커밋됐는지 — tx 경계 버그.
3. **역분개 누락**: 환불은 됐는데 (`order_recon_totals` 의 completedRefunds 와 비교)
   REVERSED 분개가 없는 케이스.
4. **조정 미반영**: `settlement_adjustments` 는 있는데 대응 분개가 없는 케이스.

## 코드 리뷰 체크

- 원장 저장 서비스에 `@Transactional` 이 journal 묶음 전체를 감싸는지.
- 역분개 생성 시 금액 부호를 뒤집지 말고 **차/대 계정을 반전**하는지 (음수 금액 row 금지).
- 조회 API 는 read-only — `GetLedgerUseCase` 계열에 쓰기 로직이 섞이면 반려.
