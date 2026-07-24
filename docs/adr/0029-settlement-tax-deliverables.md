# ADR 0029 — 정산 연계 세무 산출물 (부가세·원천징수·세금계산서) — 채택

> 리넘버링 이력: 최초 0027 로 채택되었으나 ADR 0027(DB 파티셔닝·리텐션·PK 표준, 2026-07-15 선행)과 번호가 충돌하여
> 2026-07-24 에 0029 로 재부여. 본문·코드 주석의 "ADR 0027 §…" 세무 참조는 모두 0029 를 가리킨다.

- 상태: Accepted — 2026-07-23, 구현 진행 (Seed B2)
- 관련: ADR 0026(GL 현금 폐루프), settlement-domain-rules·money-safety·ledger-invariants 스킬
- 배경: 정산 플랫폼의 세무 산출물(부가세·원천징수·세금계산서)이 코드에 전무. Seed B2 로 신설.

## 컨텍스트

정산은 `paymentAmount = netAmount + commission`(원장 구성적 균형) 으로 셀러에게 `netAmount` 를 지급하고
플랫폼이 `commission` 을 수익으로 인식한다. 그러나 세무 산출물(부가세·원천징수·세금계산서)이 없어,
플랫폼의 세무 의무가 원장·정산금과 대사되지 않는다. 세무 필드·계정·셀러 세무유형 데이터 모두 부재.

## 결정 (2026-07-23 — 세무 모델)

한국 정산 플랫폼의 통상 모델을 채택. **되돌리기 어려운 세무 정책이므로 가정을 명시하고, 실제 세무 의무는
세무 오너 재확인 대상**(포트폴리오 MVP 기준의 textbook 모델).

1. **부가세(VAT) — 포함과세(2026-07-24 정정)**: 플랫폼 수수료(commission)는 **부가세 포함** 금액으로 본다.
   - `vatAmount = commission × 10/110`(원단위 절사), 진직 수수료수익 = `commission − vatAmount`.
   - 별도 AR 청구가 없으므로 AR 무한적재 문제 소멸. 셀러 매출 자체 VAT 는 셀러 책임(범위 외).
2. **원천징수 — 실지급 통합(2026-07-24 정정)**: **개인(비사업자) 셀러** 사업소득에 **3.3% 원천징수**
   (소득세 3% + 지방소득세 0.3%). 사업자·법인 미대상.
   - `withholdingAmount = (개인 셀러) netAmount × 0.033`(원단위 절사), 사업자=0.
   - **핵심: 원천징수는 실제 지급에서 공제된다** — 셀러 실지급 = immediate − withholding.
     즉 `SettlementConfirmItemWriter` 의 즉시지급 산출을 `net − holdback − offset − withholding` 으로 개정.
     (장부만 줄이고 현금은 전액 지급하던 HIGH #4 결함 봉합.)
3. **세금계산서**: **플랫폼 → 셀러 수수료 세금계산서 발행**(플랫폼이 셀러에게 수수료 용역 제공).
   공급가액=commission, 세액=vatAmount. 셀러→플랫폼 발행은 범위 외.
4. **세무 라운딩**: Money VO(HALF_UP scale2)와 **분리**한 세무 전용 라운딩 — **원단위 절사**(1원 미만 버림).
5. **세무 계정 소속 원장(2026-07-24 정정)**:
   - **부가세**: settlement 자체원장(COMMISSION_REVENUE 가 있는 곳)에 `VAT_PAYABLE`(대변성) 신설.
     포함과세라 수수료수익 인식 시 VAT 부분을 예수로 분리: `Dr COMMISSION_REVENUE vat / Cr VAT_PAYABLE vat`.
   - **원천징수**: 실지급 통합이라 **account-service GL** 폐루프(ADR 0026)에 `WITHHOLDING_PAYABLE`(대변성) 신설.
     payout 시 실지급이 `immediate−withholding` 로 줄면 SELLER_PAYABLE 잔여(=withholding)를
     `Dr SELLER_PAYABLE / Cr WITHHOLDING_PAYABLE` 로 닫아 **폐루프 유지**. settlement 가 원천징수 이벤트 발행,
     account 소비(ADR 0024 계약). 이 부분은 ADR 0026 GL 폐루프의 확장이다.
6. **산출물 범위**: 세금계산서 **데이터 생성 + PDF 아카이빙**(shared-common GhostscriptService 재사용)까지.
   **국세청 e-Tax 실연동(XML 승인번호)은 다음 Seed 로 분리.**

## 대사 항등식 (Seed B2 수용기준 핵심)

세무 금액이 정산 금액·원장과 3자 정합해야 한다:

```
# 부가세 포함과세
vatAmount = floor(commission × 10/110)
세금계산서: 공급가액 = commission − vatAmount ∧ 세액 = vatAmount ∧ 합계 = commission
settlement 원장: Dr COMMISSION_REVENUE vatAmount / Cr VAT_PAYABLE vatAmount (차대 균형, POSTED 불변)

# 원천징수 실지급 통합
withholdingAmount = 개인? floor(netAmount × 0.033) : 0
셀러 실지급(현금) = immediate − withholdingAmount   # immediate = net − holdback − offset
account GL 폐루프 유지: payout DR SELLER_PAYABLE (immediate−withholding)/CR CASH
                       + DR SELLER_PAYABLE withholding/CR WITHHOLDING_PAYABLE
                       → SELLER_PAYABLE 잔여 0 (완전정산 통제계정 봉합, ADR 0026 §봉합)

# 3자 대사(실효화): 세무 계산 ↔ 세금계산서 ↔ 원장 에 더해
#   원장 WITHHOLDING_PAYABLE 예수분 == 실제 payout 감액분 을 교차검증(자기참조 대사 탈피)
```

## 셀러 세무유형 데이터

원천징수 판단 입력(개인/사업자·사업자등록번호)이 정산·프로젝션에 없다. 신규 cross-service 프로젝션 대신
**세무 프로필 레지스트리**(settlement-service, 관리자 CRUD — payout 의 셀러 계좌 레지스트리와 동형)로 확보.
미등록 셀러는 보수적 기본값(개인·원천징수 대상)으로 처리하거나 산출 보류(정책: 미등록=산출 보류 후 관리자 등록 유도).

## 구현 범위

Phase 1 세무 프로필 레지스트리 → 2 세무 계산 도메인(VAT·원천징수, 원단위 절사) → 3 세무 원장 전표+3자 대사 →
4 세금계산서 도메인+PDF → 5 배선·게이트. 전부 settlement-service 자족형(cross-service 이벤트 0).

## 재확인 필요 (세무 오너)

textbook 모델 가정 — 실제 부가세 과세대상·원천징수 의무·세금계산서 발행관계는 플랫폼의 실 세무 자문으로 확정 대상.
본 ADR 은 MVP 구현의 근거이며, 실 운영 전 세무 검토가 선행돼야 한다.
