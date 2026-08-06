---
name: sentiment-signals
description: 기업 뉴스·평판 스코어(company-service) 해석 규칙 — 정성 리스크 필터. 종목 진단의 B8/S3 판정, 악재 확인 시 로드.
---

# 뉴스·평판 신호 해석 (company-service)

company-service 는 네이버 뉴스 수집(제목·요약·링크, 본문 미저장) + LLM 감성분석 기반
평판 스코어를 제공한다. 이 신호는 **필터**이지 매수 근거가 아니다 —
"평판이 좋아서 산다"가 아니라 "평판 악재가 없어서 통과"로만 쓴다.

## 응답 구조 (reputation_score)

- `latest.score`: 0~100 / `latest.grade`: A(≥80) B(≥60) C(≥40) D(≥20) E(<20)
- `articleCount` / `positiveCount` / `negativeCount` / `neutralCount` — 개별 기사 sentiment 는
  미노출, 집계만 제공
- `negativeByCategory`: 부정 기사 카테고리별 건수 — `FINANCIAL`(회계·재무) `LEGAL`(법적)
  `GOVERNANCE`(지배구조) `LABOR`(노사) `PRODUCT`(제품·서비스), 건수 > 0 인 것만 포함
- `snapshotDate`/`calculatedAt` — 산출 기준일 (오래됐으면 명시)
- `latest = null` 이면 **미산정(204)** — "부정 없음"이 아니라 "신호 없음"이다

## B8 (매수 체크리스트 정성 필터) 판정

1. `reputation_score(stockCode)` — 등급·부정 카테고리 확인
2. `news_recent(stockCode)` — 최근 기사에서 부정 기사 실물 확인 (제목·날짜·링크 인용)

판정:
- **통과**: grade C 이상 **그리고** negativeByCategory 에 LEGAL/FINANCIAL 없음
- **미충족**: grade D/E 또는 LEGAL/FINANCIAL 부정 존재 — 대표 기사 2~3건 인용 + 분류
- **unknown**: latest=null (미산정) — 표본 부족 가능성을 명시하고 충족으로 세지 마라

## 악재 분류 (negativeByCategory ↔ 심각도)

| 카테고리 | 예 | 취급 |
|---|---|---|
| LEGAL·FINANCIAL | 분식 의혹, 압수수색, 상장폐지 사유 | **하드 필터** — 다른 지표 무관 보류 |
| GOVERNANCE | 오너 리스크, 경영권 분쟁 | 미충족 + 해소 전 보류 권고 |
| LABOR | 파업, 중대재해 | 지속 기간·규모로 판단, 실적 영향 교차 확인 |
| PRODUCT | 리콜, 품질 이슈 | 재무 데이터로 교차 확인 (S1 연동) |
| (뉴스 노이즈) | 주가 등락 중계, 리포트 인용 | 판정에 미반영 |

## 함정

- **감성분석은 LLM 판정**이다 — 반어·중의적 제목은 오분류될 수 있다. 점수가 재무·거시와
  크게 어긋나면 원 기사 제목을 직접 읽고 재판정하라.
- 기사 수 자체가 적은 중소형주는 "신호 없음"이지 "긍정"이 아니다 — 표본 부족을 명시하라.
- 호재 뉴스 급증도 주의: 테마성 급등 신호일 수 있다 (`risk-management` 의 추격 매수 금지).
- 뉴스는 제목·요약만 저장된다(저작권) — 인용 시 링크를 함께 제공하라.
